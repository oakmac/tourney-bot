(ns tourneybot-admin.core
  (:require
    [cljsjs.moment]
    [clojure.data :refer [diff]]
    [clojure.string :refer [blank? lower-case replace]]
    [goog.functions :refer [once]]
    [rum.core :as rum]
    [tourneybot.data :refer [ensure-tournament-state
                             final-status
                             game-finished?
                             game-statuses
                             games->results
                             in-progress-status
                             is-swiss-game?
                             scheduled-status
                             teams-already-played?]]
    [tourneybot.util :refer [always-nil
                             atom-logger
                             by-id
                             fetch-ajax-text
                             fetch-json-as-cljs
                             index-key-fn-mixin
                             js-log
                             log
                             neutralize-event
                             one?
                             tourney-bot-url]]
    [tourneybot-admin.api :refer [check-password
                                  update-event!]]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

;; TODO: allow this to be overriden with a query param
(def refresh-rate-ms 5000)

(def in-dev-mode? (not= -1 (.indexOf js/document.location.href "devmode")))

(when in-dev-mode?
  (js-log "TourneyBot dev mode started."))

(def saving-txt "Saving…")

;;------------------------------------------------------------------------------
;; Misc
;;------------------------------------------------------------------------------

(defn tournament-state-url []
  (if in-dev-mode?
    "../tournament.json"
    (str "../api/latest.php?_c=" (random-uuid))))

(defn- add-sign-to-num [n]
  (if (neg? n)
    (str n)
    (str "+" n)))

(defn- get-games-in-group
  "Returns all the games in a given group-id.
   NOTE: this function should probably be moved to the common namespace."
  [all-games group-id]
  (let [games-coll (map (fn [[game-id game]] (assoc game :game-id game-id))
                        all-games)]
    (if (= group-id "all-games")
      games-coll
      (filter
        (fn [game]
          (= group-id (:group game)))
        games-coll))))

(def date-format "YYYY-MM-DD HHmm")

(defn- format-time [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "h:mma")))

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {;; login related
   :logging-in? false
   :password ""
   :password-error? false
   :password-valid? false

   ;; modals
   :menu-showing? false
   :loading-modal-showing? false
   :loading-modal-txt ""
   :edit-game-modal-showing? false
   :edit-game-modal-game nil
   :error-modal-showing? false

   ;; active page / group-id
   :active-page "teams"})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

(defn- valid-page-state?
  "Some simple predicates to make sure the state is valid."
  [new-state]
  (and (map? new-state)))
  ;; TODO: write some more predicates for this

(set-validator! page-state valid-page-state?)

;;------------------------------------------------------------------------------
;; Save UI-specific app state to localStorage
;;------------------------------------------------------------------------------

;; TODO: change this to just be their password or session key

(def ls-key "admin-page-state")

;; load any existing client state on startup
(try
  (when-let [state-string (js/window.localStorage.getItem ls-key)]
    (let [js-state (try (js/JSON.parse state-string)
                     (catch js/Error _error nil))]
      (when (object? js-state)
        (let [clj-state (js->clj js-state :keywordize-keys true)
              ;; always reset password UI state on page load
              ;; NOTE: this is a quick hack
              clj-state (assoc clj-state :logging-in? false
                                         :password-error? false)]
          (swap! page-state merge clj-state)))))
  (catch js/Error e nil))

(defn- save-client-state [_kwd _the-atom _old-state new-state]
  (let [ui-only-state (select-keys new-state [:password])
        js-state (clj->js ui-only-state)
        js-state-string (js/JSON.stringify js-state)]
    (try
      (js/window.localStorage.setItem ls-key js-state-string)
      (catch js/Error e nil))))

(add-watch page-state :client-state save-client-state)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

(defn- fetch-tourney-state-success [new-state]
  ;; TODO: make sure the version is the latest here
  ;; merge the tournament state with the page state
  (swap! page-state merge new-state)
  ;; set the title tag
  (aset js/document "title" (str (:title new-state) " - Admin")))

(defn- fetch-tourney-state! []
  (fetch-json-as-cljs (tournament-state-url) fetch-tourney-state-success))

;; Continually fetch the latest tournament state and merge it with the page
;; state atom.
(when-not in-dev-mode?
  (js/setInterval fetch-tourney-state! refresh-rate-ms))

;; kick off the first fetch
(fetch-tourney-state!)

;;------------------------------------------------------------------------------
;; Save Tournament State
;;------------------------------------------------------------------------------

;; NOTE: this list should probably be in common or tourney-nerd?
(def game-state-keys
  "page-state map keys that we want to save on the server"
  #{:teams :games :version :title :tiesAllowed})

(defn- generic-success-fn []
  (swap! page-state assoc :loading-modal-showing? false))

(defn- generic-error-fn []
  (swap! page-state assoc :error-modal-showing? true
                          :loading-modal-showing? false))

(defn- save-state!
  ([]
   (save-state! generic-success-fn generic-error-fn))
  ([success-fn]
   (save-state! success-fn generic-error-fn))
  ([success-fn error-fn]
   ;; bump the version
   (swap! page-state update-in [:version] inc)
   ;; send the new state
   (let [current-state @page-state
         game-state (select-keys current-state game-state-keys)]
     (update-event! (:password current-state) game-state success-fn error-fn))))

;;------------------------------------------------------------------------------
;; SVG Icon
;;------------------------------------------------------------------------------

(rum/defc SVGIcon < rum/static
  [svg-class icon-id]
  [:svg
    {:class svg-class
     :dangerouslySetInnerHTML
       {:__html (str "<use xlink:href='../img/icons.svg#" icon-id "'></use>")}}])

;;------------------------------------------------------------------------------
;; Swiss Results Table
;;------------------------------------------------------------------------------

;; TODO: take "tiesAllowed?" into account here
(rum/defc SwissResultsRow < (merge rum/static index-key-fn-mixin)
  [idx {:keys [team-name games-won games-lost games-tied
               points-won points-lost points-diff
               victory-points]}]
  [:tr
    [:td.place (str "#" (inc idx))]
    [:td.name team-name]
    [:td (str games-won "-" games-lost "-" games-tied)]
    [:td (str "+" points-won ","
              "-" (js/Math.abs points-lost) ","
              (add-sign-to-num points-diff))]
    [:td (add-sign-to-num victory-points)]])

(rum/defc SwissResultsTHead < rum/static
  []
  [:thead
    [:tr
      [:th.place {:style {:width "5%"}}]
      [:th.name {:style {:width "45%"}} "Name"]
      [:th "Record"]
      [:th "Points"]
      [:th "Score"]]])

(rum/defc SwissResultsTable < rum/static
  [results]
  [:table.small-results-tbl
    (SwissResultsTHead)
    [:tbody
      (map-indexed SwissResultsRow results)]])

;;------------------------------------------------------------------------------
;; Next Round Matchups
;;------------------------------------------------------------------------------

(rum/defc Matchup < rum/static
  [games [resultA resultB]]
  (let [teamA-id (:team-id resultA)
        teamB-id (:team-id resultB)
        teamA-name (:team-name resultA)
        teamB-name (:team-name resultB)
        vpointsA (:victory-points resultA)
        vpointsB (:victory-points resultB)
        already-played? (teams-already-played? teamA-id teamB-id games)]
    [:li
      (str teamA-name " (")
      [:span.score (add-sign-to-num vpointsA)]
      ")"
      [:span.vs "vs"]
      (str teamB-name " (")
      [:span.score (add-sign-to-num vpointsB)]
      ")"
      (when already-played?
        [:span.already-played
          (str "Whoops! These two teams already played in " (:name already-played?))])]))

;;------------------------------------------------------------------------------
;; Next Round Simulator
;;------------------------------------------------------------------------------

;; TODO: far better than a "next round simulator" would be a
;;       "guaranteed next round matchup algorithm"

;; TODO: it would be nice if the matchups automatically populated the next round
;;       games if the teams had not played before

(defn- on-change-simulated-input [kwd js-evt]
  (let [new-score (int (aget js-evt "currentTarget" "value"))]
    (swap! page-state assoc kwd new-score)))

(rum/defc NextRoundSimulator < rum/static
  [teams games-for-this-round [final-game-id final-game]
   simulated-scoreA simulated-scoreB]
  (let [teamA-id (keyword (:teamA-id final-game ""))
        teamB-id (keyword (:teamB-id final-game ""))
        teamA-name (get-in teams [teamA-id :name] "")
        teamB-name (get-in teams [teamB-id :name] "")
        simulated-game [:simulated-game (merge final-game {:status final-status
                                                           :scoreA simulated-scoreA
                                                           :scoreB simulated-scoreB})]
        simulated-games (conj games-for-this-round simulated-game)
        simulated-results (games->results teams simulated-games)]
    [:div.simulated-container
      [:h3 "Last Game Simulated Results"]
      [:div.input-row
        [:input {:on-change (partial on-change-simulated-input :simulated-scoreA)
                 :min "0"
                 :max "100"
                 :type "number"
                 :value simulated-scoreA}]
        [:span.team-name teamA-name]]
      [:div.input-row
        [:input {:on-change (partial on-change-simulated-input :simulated-scoreB)
                 :min "0"
                 :max "100"
                 :type "number"
                 :value simulated-scoreB}]
        [:span.team-name teamB-name]]
      (SwissResultsTable simulated-results)]))

;; TODO: calculate this from the games data instead of hard-coding
(def last-swiss-round-num 5)

(rum/defc SwissPanel < rum/static
  [teams all-games swiss-round simulated-scoreA simulated-scoreB]
  (let [prev-swiss-round (dec swiss-round)
        next-swiss-round (inc swiss-round)
        all-swiss-games (filter #(is-swiss-game? (second %)) all-games)
        ;; get all the games from the previous round and below
        ;; NOT including this current round
        prev-round-games (filter #(<= (:swiss-round (second %)) prev-swiss-round)
                                 all-swiss-games)
        ;; is the previous round finished?
        prev-round-finished? (and (not (empty? prev-round-games))
                                  (every? game-finished? (vals prev-round-games)))
        ;; calculate results for the previous round
        prev-round-results (games->results teams prev-round-games)
        ;; get all the games for this swiss round and below
        games-for-this-round (filter #(<= (:swiss-round (second %)) swiss-round)
                                     all-swiss-games)
        ;; calculate the results for this round
        results (games->results teams games-for-this-round)
        ;; get all the games in only this swiss round
        games-only-in-this-round (filter #(= (:swiss-round (second %)) swiss-round)
                                         games-for-this-round)
        num-games-in-this-round (count games-only-in-this-round)
        ;; are all the games in this swiss round finished?
        num-games-finished (count (filter #(= (:status (second %)) final-status)
                                          games-only-in-this-round))
        all-finished? (= num-games-in-this-round num-games-finished)
        one-game-left? (= num-games-in-this-round (inc num-games-finished))
        final-game-in-this-round (when one-game-left?
                                   (first (filter #(not= (:status (second %)) final-status)
                                                  games-only-in-this-round)))]
    [:div.swiss-panel-container
      (when prev-round-finished?
        [:div
          [:h3 (str "Swiss Round #" swiss-round " Matchups")]
          [:ul.matchups
            (map (partial Matchup prev-round-games) (partition 2 prev-round-results))]])

      (when-not (zero? num-games-finished)
        [:div
          [:h3 (str "Swiss Round #" swiss-round " Results")
            [:span.game-count (str "(" num-games-finished "/" num-games-in-this-round " games played)")]]
          (SwissResultsTable results)

          (when-not all-finished?
            (if one-game-left?
              (NextRoundSimulator teams games-for-this-round final-game-in-this-round simulated-scoreA simulated-scoreB)
              [:p.msg (str "When there is one game left in this round, you will be "
                           "able to simulate the results of the last game here.")]))

          (when (and all-finished?
                     (not= swiss-round last-swiss-round-num))
            [:p.msg
              (str "This round is over. Match-ups for the next round can be "
                   "found on the \"Swiss Round " next-swiss-round "\" tab.")])])]))

;;------------------------------------------------------------------------------
;; Single Game Row
;;------------------------------------------------------------------------------

(defn- on-change-game-name [game-id js-evt]
  (let [new-name (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games (keyword game-id) :name] new-name)))

(defn- on-change-start-time [game-id js-evt]
  (let [new-time (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games (keyword game-id) :start-time] new-time)))

;; TODO: need an on-blur function to make sure the time is formatted properly



;; TODO: prevent them from picking the same team for teamA and teamB

(defn- compare-games [a b]
  (compare (-> a second :start-time)
           (-> b second :start-time)))

;; TODO: do not allow a game to reach "Finished" state if the scores are equal
;;       and ties are not allowed

(defn- on-change-score [game-id score-key js-evt]
  (let [new-score (int (aget js-evt "currentTarget" "value"))
        game-id (keyword game-id)]
    (swap! page-state assoc-in [:games game-id score-key] new-score)
    ;; games with any points cannot have status "scheduled"
    (when (= scheduled-status (get-in @page-state [:games game-id :status] scheduled-status))
      (swap! page-state assoc-in [:games game-id :status] in-progress-status))))

(defn- on-change-status [game-id status-val js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc-in [:games (keyword game-id) :status] status-val)
  ;; reset the simulated scores anytime a game is marked as Finished
  (when (= status-val final-status)
    (swap! page-state assoc :simulated-scoreA 0
                            :simulated-scoreB 0)))

(rum/defc ScoreInput < rum/static
  [game-id current-score score-key finished?]
  (if finished?
    [:span.final-score (str current-score)]
    [:input
      {:on-change (partial on-change-score game-id score-key)
       :max "100"
       :min "0"
       :type "number"
       :value current-score}]))

(def status-text
  {scheduled-status "Scheduled"
   in-progress-status "In Progress"
   final-status "Finished"})

(rum/defc StatusInput < rum/static
 [game-id status-val current-status disabled?]
 (let [selected? (= status-val current-status)]
   (if disabled?
     [:span.htab.disabled (get status-text status-val)]
     [:span
       {:class (str "htab" (when selected? " active"))
        :on-click (partial on-change-status game-id status-val)
        :on-touch-start (partial on-change-status game-id status-val)}
       (get status-text status-val)])))

;;------------------------------------------------------------------------------
;; Edit Game Modal
;;------------------------------------------------------------------------------

(defn- click-edit-game-cancel-btn [js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :edit-game-modal-showing? false
                          :edit-game-modal-game nil))

(defn- click-edit-game-save-btn [js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :loading-modal-showing? true
                          :loading-modal-txt saving-txt)

  ;; ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZzz
  ;; THIS IS SUPER IMPORTANT
  ;; ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZzz
  ;; TODO: update the version and send the new state object
  (js/setTimeout
    (fn [] (swap! page-state assoc :loading-modal-showing? false
                                   :edit-game-modal-showing? false
                                   :edit-game-modal-game nil))
    1500))
  ;; ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZzz
  ;; THIS IS SUPER IMPORTANT
  ;; ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZzz

(defn- click-score-up [scoreX js-evt]
  (neutralize-event js-evt)
  (swap! page-state update-in [:edit-game-modal-game scoreX] inc))

(defn- click-score-down [scoreX js-evt]
  (neutralize-event js-evt)
  (swap! page-state update-in [:edit-game-modal-game scoreX] dec))

(defn- click-status-tab [new-status js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc-in [:edit-game-modal-game :status] new-status))

(defn- on-change-team-dropdown [game-id team-key js-evt]
  (let [team-id (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:edit-game-modal-game team-key] team-id)))

;; TODO: put the team record in here
(rum/defc TeamOption < (merge rum/static index-key-fn-mixin)
  [idx [team-id team]]
  [:option {:value (name team-id)}
    (:name team)])

(defn- compare-team-name [[teamA-id teamA] [teamB-id teamB]]
  (compare (:name teamA) (:name teamB)))

(rum/defc TeamSelect < rum/static
  [teams game-id game team-key]
  (let [sorted-teams (sort compare-team-name teams)]
    [:select.team-select
      {:on-change (partial on-change-team-dropdown game-id team-key)
       :value (team-key game)}
      [:option {:value ""} "-- Select a Team --"]
      (map-indexed TeamOption sorted-teams)]))

(rum/defc EditGameModalBodyTeams < rum/static
  [teams game]
  (let [game-id (:game-id game)
        game-name (:name game)
        start-time (:start-time game)
        teamA-id (:teamA-id game)
        teamB-id (:teamB-id game)]
    [:div.fullscreen-modal-6d79e
      [:div.wrapper-50f2f
        [:div.top-d8bc3
          [:h3.title-eef62 game-name]
          [:div.flex-container-ac723
            [:div.left-02b94
              (TeamSelect teams game-id game :teamA-id)]
            [:div.center-f5e42
              [:div.small-vs-fb4ff [:span "vs"]]]
            [:div.right-6c20f
              (TeamSelect teams game-id game :teamB-id)]]]
        [:div.bottom-5fd4c
          [:button.btn-215d7 {:on-click click-edit-game-cancel-btn}
            "Cancel"]
          [:div.spacer-b3729]
          [:button.btn-primary-7f246 {:on-click click-edit-game-save-btn}
            "Save & Close"]]]]))

;; TODO: come up with a visual "disabled" state for the "-1" buttons when
;;       score == 0
(rum/defc EditGameModalBodyScores < rum/static
  [{:keys [game-id
           name
           scoreA
           scoreB
           start-time
           status
           teamA-id
           teamB-id]}]
  (let [teamA-name (get-in @page-state [:teams (keyword teamA-id) :name])
        teamB-name (get-in @page-state [:teams (keyword teamB-id) :name])
        ;; make sure score is a number
        scoreA (if-not (number? scoreA) 0 scoreA)
        scoreB (if-not (number? scoreB) 0 scoreB)]
    [:div.fullscreen-modal-6d79e
      [:div.wrapper-50f2f
        [:div.top-d8bc3
          [:h3.title-eef62 name]
          [:div.flex-container-ac723
            [:div.left-02b94
              [:div.big-name-c7484 [:span teamA-name]]
              [:button.score-btn-261c4
                {:on-click (partial click-score-up :scoreA)
                 :on-touch-start (partial click-score-up :scoreA)}
                "+1"]
              [:div.big-score-39b8b scoreA]
              [:button.score-btn-261c4.red
                {:on-click (when-not (zero? scoreA) (partial click-score-down :scoreA))
                 :on-touch-start (when-not (zero? scoreA) (partial click-score-down :scoreA))}
                "-1"]]
            [:div.center-f5e42
              [:div.small-vs-fb4ff [:span "vs"]]]
            [:div.right-6c20f
              [:div.big-name-c7484 [:span teamB-name]]
              [:button.score-btn-261c4
                {:on-click (partial click-score-up :scoreB)
                 :on-touch-start (partial click-score-up :scoreB)}
                "+1"]
              [:div.big-score-39b8b scoreB]
              [:button.score-btn-261c4.red
                {:on-click (when-not (zero? scoreB) (partial click-score-down :scoreB))
                 :on-touch-start (when-not (zero? scoreB) (partial click-score-down :scoreB))}
                "-1"]]]
          [:div.status-tabs-61ba0
            [:div {:class (when (= status scheduled-status) "active-32daa")
                   :on-click (partial click-status-tab scheduled-status)
                   :on-touch-start (partial click-status-tab scheduled-status)}
              "Scheduled"]
            [:div {:class (when (= status in-progress-status) "active-32daa")
                   :on-click (partial click-status-tab in-progress-status)
                   :on-touch-start (partial click-status-tab in-progress-status)}
              "In Progress"]
            [:div {:class (when (= status final-status) "active-32daa")
                   :on-click (partial click-status-tab final-status)
                   :on-touch-start (partial click-status-tab final-status)}
              "Final"]]]
        [:div.bottom-5fd4c
          [:button.btn-215d7 {:on-click click-edit-game-cancel-btn}
            "Cancel"]
          [:div.spacer-b3729]
          [:button.btn-primary-7f246 {:on-click click-edit-game-save-btn}
            "Save & Close"]]]]))

(defn- game-has-teams-set? [game]
  (and (string? (:teamA-id game))
       (not (blank? (:teamA-id game)))
       (string? (:teamB-id game))
       (not (blank? (:teamB-id game)))))

(rum/defc EditGameModal < rum/static
  [teams game]
  [:div
    [:div.modal-layer-20e76]
    (if (game-has-teams-set? game)
      (EditGameModalBodyScores game)
      (EditGameModalBodyTeams teams game))])

;;------------------------------------------------------------------------------
;; Teams
;;------------------------------------------------------------------------------

(defn- change-team-name [js-evt]
  (let [new-txt (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:edit-team :name] new-txt)))

(defn- change-captain-name [js-evt]
  (let [new-txt (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:edit-team :captain] new-txt)))

(defn- close-edit-team-modal! [js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :edit-team-modal-showing? false
                          :edit-team nil))

(defn- click-edit-team-save-btn [js-evt]
  (neutralize-event js-evt)
  (let [edit-team (:edit-team @page-state)
        new-captain (:captain edit-team)
        new-name (:name edit-team)
        team-id (:id edit-team)]
    ;; update the UI state
    (swap! page-state assoc :edit-team-modal-showing? false
                            :edit-team nil
                            :loading-modal-showing? true
                            :loading-modal-txt saving-txt)
    ;; update the team
    (swap! page-state update-in [:teams team-id] merge
      {:name new-name
       :captain new-captain})
    ;; save the new state
    (save-state!)))

(rum/defc EditTeamModalBody < rum/static
  [{:keys [id name captain]}]
  [:div.edit-team-modal-bf210
    [:div.wrapper-50f2f
      [:div.top-d8bc3
        [:div.inner-8cd7f
          [:label.label-cee3e "Team Name:"]
          [:input.big-input-e8342
            {:on-change change-team-name
             :type "text"
             :value name}]
          [:label.label-cee3e "Captain:"]
          [:input.big-input-e8342
            {:on-change change-captain-name
             :type "text"
             :value captain}]]]
      [:div.bottom-5fd4c
        [:button.btn-215d7 {:on-click close-edit-team-modal!}
          "Cancel"]
        [:div.spacer-b3729]
        [:button.btn-primary-7f246 {:on-click click-edit-team-save-btn}
          "Save & Close"]]]])

(rum/defc EditTeamModal < rum/static
  [team]
  [:div
    [:div.modal-layer-20e76 {:on-click close-edit-team-modal!}]
    (EditTeamModalBody team)])

(defn- click-edit-team-btn [team js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :edit-team-modal-showing? true
                          :edit-team team))

(rum/defc TeamRow < (merge rum/static index-key-fn-mixin)
  [idx team]
  [:div.team-row-f5ef3
    [:div.row-left-87ce4
      [:div.team-name-37bbb (:name team)]
      [:div.captain-ea163 (:captain team)]]
    [:div.row-right-eb8a9
      [:button.btn-secondary-a6e0d
        {:on-click (partial click-edit-team-btn team)}
        "Edit"]]])

(rum/defc TeamsPage < rum/static
  [teams]
  (let [teams-list (map
                     (fn [[team-id team]] (assoc team :id team-id))
                     teams)]
    [:div
      (map-indexed #(TeamRow %1 %2) teams-list)]))

;;------------------------------------------------------------------------------
;; Games Body
;;------------------------------------------------------------------------------

(defn- click-edit-game [game-id js-evt]
  (neutralize-event js-evt)
  ;; NOTE: this should always be true, just being defensive
  (when-let [game-to-edit (get-in @page-state [:games game-id])]
    (swap! page-state assoc :edit-game-modal-showing? true
                            :edit-game-modal-game game-to-edit)))

(rum/defc NoTeamsName < rum/static
  [name]
  [:span.scheduled-c1f27 name])

(defn- status-name [s]
  (if (= s "in_progress")
    "in progress"
    s))

(rum/defc GameName < rum/static
  [{:keys [name teamA-id teamB-id scoreA scoreB status]}]
  (let [teams (:teams @page-state)
        teamA-name (get-in teams [(keyword teamA-id) :name])
        teamB-name (get-in teams [(keyword teamB-id) :name])]
    [:div.game-row-02b81
      [:div
        [:span.team-name (str teamA-name
                              (when (or (= status in-progress-status) (= status final-status))
                                (str " (" scoreA ")")))]
        [:span.vs "vs"]
        [:span.team-name (str (when (or (= status in-progress-status) (= status final-status))
                                (str "(" scoreB ") "))
                              teamB-name)]]
      [:div
        [:span.status (status-name status)]
        [:span.game-name (str " - " name)]]]))

(rum/defc GameRow2 < (merge rum/static index-key-fn-mixin)
  [idx game]
  (let [{:keys [game-id name start-time status teamA-id teamB-id]} game]
    [:tr {:class (if (even? idx) "even-fa3d0" "odd-fd05d")}
      [:td.time-cell-717e2 (format-time start-time)]
      [:td
        (if (or (not teamA-id) (not teamB-id))
          (NoTeamsName name)
          (GameName game))]
      [:td
        [:button.edit-btn-7da0b
          {:on-click (partial click-edit-game game-id)
           :on-touch-start (partial click-edit-game game-id)}
          "Edit"]]]))

(def page-titles
  "Mapping of page-ids to titles."
  {"swiss-round-1" "Swiss Round 1"
   "swiss-round-2" "Swiss Round 2"
   "swiss-round-3" "Swiss Round 3"
   "swiss-round-4" "Swiss Round 4"
   "swiss-round-5" "Swiss Round 5"
   "bracket-play" "Bracket Play"
   "all-games" "All Games"})

(rum/defc GamesList < rum/static
  [teams all-games page-id]
  (let [title (get page-titles page-id)
        games (get-games-in-group all-games page-id)
        is-swiss-round? (every? is-swiss-game? games)
        is-bracket-play? false
        swiss-round (:swiss-round (first games) false)]
        ; games-for-this-swiss-round (filter #(<= (:swiss-round (second %)) swiss-round)
        ;                                    (vals games))
        ; swiss-round-results (games->results)]
    [:section
      [:h2.title-eef62 title]
      [:div.flex-052ba
        [:div.col-beeb5
          [:table.tbl-988bd
            [:tbody
              (map-indexed GameRow2 (sort-by :start-time games))]]]
        (when (or is-swiss-round? is-bracket-play?)
          [:div.spacer-b3729])
        (when is-swiss-round?
          [:div.col-beeb5
            ; [:h2.title-eef62 (str title " Results")]
            ; (SwissResultsTable swiss-round-results)])
            (SwissPanel teams all-games swiss-round 0 0)])
        (when is-bracket-play?
          [:div "TODO: bracket display goes here"])]]))

;;------------------------------------------------------------------------------
;; Footer
;;------------------------------------------------------------------------------

;; TODO: link to the client site from the admin page, open in a new window
(rum/defc Footer < rum/static
  []
  [:footer
    [:div.left
      "powered by " [:a {:href tourney-bot-url} "TourneyBot"]]])

;;------------------------------------------------------------------------------
;; Password Page
;;------------------------------------------------------------------------------

(defn- on-change-password [js-evt]
  (let [new-password (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc :password new-password)))

(defn- check-password-success []
  (swap! page-state assoc :logging-in? false
                          :password-error? false
                          :password-valid? true))

(defn- check-password-error []
  (swap! page-state assoc :logging-in? false
                          :password ""
                          :password-error? true
                          :password-valid? false))

(defn- click-login-btn [js-evt]
  (when js-evt
    (neutralize-event js-evt))
  (swap! page-state assoc :logging-in? true
                          :password-error? false
                          :password-valid? false)
  (if in-dev-mode?
    ;; simulate the API call
    (js/setTimeout check-password-success (+ 200 (rand-int 200)))
    ;; else make the actual API call
    (let [password (:password @page-state)]
      (check-password password check-password-success check-password-error))))

(def password-input-id (random-uuid))

(rum/defc PasswordPage < rum/static
  [{:keys [logging-in? password password-error? title]}]
  [:div.password-container
    [:div.login-box
      [:h1 title]
      [:form.inner
        [:input {:disabled logging-in?
                 :id password-input-id
                 :on-change on-change-password
                 :placeholder "Password"
                 :type "password"
                 :value password}]
        (when password-error?
          [:div.wrong "Wrong password"])
        (if logging-in?
          [:button.login-btn.disabled {:disabled true}
            "Logging in..."]
          [:input.login-btn
            {:on-click click-login-btn
             :on-touch-start click-login-btn
             :type "submit"
             :value "Login"}])]]])

;;------------------------------------------------------------------------------
;; Error Modal
;;------------------------------------------------------------------------------

(defn- click-reload-btn [js-evt]
  (neutralize-event js-evt)
  (js/location.reload))

(rum/defc ErrorModal < rum/static
  [txt]
  [:div
    [:div.modal-layer2-667e1]
    [:div.error-modal-c1d65
      [:div.modal-inner-e530d
        [:div.error-bar-2cd03
          (SVGIcon "error-icon-82a21" "warningTriangle")
          "Update Failed"]
        [:p.help-msg-f56fd "Please reload the page and try again."]
        [:div.centered-bafb6
          [:button.btn-primary-7f246 {:on-click click-reload-btn}
            "Reload"]]]]])

;;------------------------------------------------------------------------------
;; Loading Modal
;;------------------------------------------------------------------------------

(rum/defc LoadingModal < rum/static
  [txt]
  [:div
    [:div.modal-layer2-667e1]
    [:div.loading-modal-0a203
      [:div.wrapper-d214e
        (SVGIcon "spinny-846e4" "cog")
        txt]]])

;;------------------------------------------------------------------------------
;; Menu
;;------------------------------------------------------------------------------

(defn- close-modal [js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :menu-showing? false))

(defn- click-menu-link [page-id js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :active-page page-id
                          :menu-showing? false))

(declare click-sign-out)

(rum/defc LeftNavMenu < rum/static
  []
  [:div
    [:div.modal-layer-20e76 {:on-click close-modal}]
    [:div.modal-body-41add
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "teams")} "Teams"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "all-games")} "All Games"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-1")} "Swiss Round 1"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-2")} "Swiss Round 2"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-3")} "Swiss Round 3"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-4")} "Swiss Round 4"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-5")} "Swiss Round 5"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "bracket-play")} "Bracket Play"]
      [:div.menu-link-14aa1 {:on-click click-sign-out}
        (SVGIcon "signout-7f21d" "signOut") "Sign out"]]])

;;------------------------------------------------------------------------------
;; Header
;;------------------------------------------------------------------------------

(defn- click-menu-btn [js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :menu-showing? true))

(defn- click-sign-out [js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :logging-in? false
                          :menu-showing? false
                          :password ""
                          :password-valid? false
                          :password-error? false))

(rum/defc Header < rum/static
  [title]
  [:header
    [:button.menu-btn-6a131
      {:on-click click-menu-btn
       :on-touch-start click-menu-btn}
      "Menu"]
    [:h1.page-title-0fbc4 title]])

;;------------------------------------------------------------------------------
;; Admin App
;;------------------------------------------------------------------------------

(rum/defc AdminApp < rum/static
  [{:keys [active-page
           edit-game-modal-game
           edit-game-modal-showing?
           edit-team-modal-showing?
           edit-team
           error-modal-showing?
           games
           loading-modal-showing?
           loading-modal-txt
           menu-showing?
           teams
           title]}]
  [:div.admin-container
    (Header title)
    (if (= active-page "teams")
      (TeamsPage teams)
      (GamesList teams games active-page))
    (Footer)
    (when menu-showing?
      (LeftNavMenu))
    (when loading-modal-showing?
      (LoadingModal loading-modal-txt))
    (when error-modal-showing?
      (ErrorModal))
    (when edit-game-modal-showing?
      (EditGameModal teams edit-game-modal-game))
    (when edit-team-modal-showing?
      (EditTeamModal edit-team))])

;;------------------------------------------------------------------------------
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc AdminPage < rum/static
  [state]
  (if (:password-valid? state)
    (AdminApp state)
    (PasswordPage state)))

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "adminContainer"))

(defn- on-change-page-state
  "Render the page on every state change."
  [_kwd _the-atom _old-state new-state]
  (rum/mount (AdminPage new-state) app-container-el))

(add-watch page-state :render-loop on-change-page-state)

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

(def init!
  "Initialize the Admin page."
  (once
    (fn []
      ;; do they have the password in localStorage?
      (if-not (blank? (:password @page-state))
        ;; attempt to login
        (click-login-btn nil)
        ;; else trigger an initial render and put the focus in the password field
        (do (swap! page-state identity)
            (when-let [input-el (by-id password-input-id)]
              (.focus input-el)))))))

(init!)
