(ns tourneybot-admin.core
  (:require
    [cljsjs.marked]
    [cljsjs.moment]
    [clojure.data :refer [diff]]
    [clojure.string :refer [blank? lower-case replace]]
    [goog.functions :refer [once]]
    [rum.core :as rum]
    [tourneybot.data :refer [ensure-tournament-state
                             finished-status
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
                             js-log
                             neutralize-event
                             log
                             one?
                             tourney-bot-url]]
    [tourneybot-admin.api :refer [check-password
                                  update-event!]]))

;; TODO: set up some logic such that when a quarterfinals game is finished, it
;;       automatically seeds the team into the next bracket game

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

;; TODO: allow this to be overriden with a query param
(def refresh-rate-ms 5000)

(def in-dev-mode? (not= -1 (.indexOf js/document.location.href "devmode")))

(when in-dev-mode?
  (js-log "TourneyBot dev mode started."))

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
    (filter
      (fn [game]
        (= group-id (:group game)))
      games-coll)))

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
   :loading-modal-showing? false
   :loading-modal-txt ""
   :menu-showing? false

   ;; active page / group-id
   :active-page "swiss-round-1"})

   ; :simulated-scoreA 0
   ; :simulated-scoreB 0})

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

(def ui-only-page-state-keys (keys initial-page-state))

(defn- save-client-state [_kwd _the-atom _old-state new-state]
  (let [ui-only-state (select-keys new-state ui-only-page-state-keys)
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
;; Update games when the state changes
;;------------------------------------------------------------------------------

; (def upload-rate-ms 2500)

; ;; FIXME: change this to be "update event", not just games
; (defn- upload-games!
;   "Upload the games to tournament.json"
;   []
;   (let [{:keys [games password password-valid?]} @page-state]
;     (when (and @initial-state-loaded?
;                password-valid?
;                (not (blank? password))
;                (map? games))
;       (update-event! password games always-nil always-nil))))

; (when-not in-dev-mode?
;   (js/setInterval upload-games! upload-rate-ms))

;;------------------------------------------------------------------------------
;; SVG Icon
;;------------------------------------------------------------------------------

(rum/defc SVGIcon < rum/static
  [svg-class icon-id]
  [:svg
    {:class svg-class
     :dangerouslySetInnerHTML
       {:__html (str "<use xlink:href='../img/icons.svg#" icon-id "' />")}}])

;;------------------------------------------------------------------------------
;; Swiss Results Table
;;------------------------------------------------------------------------------

;; TODO: take "ties-allowed?" into account here
(rum/defc SwissResultsRow < rum/static
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
        simulated-game [:simulated-game (merge final-game {:status finished-status
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
(def last-swiss-round-num 4)

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
        num-games-finished (count (filter #(= (:status (second %)) finished-status)
                                          games-only-in-this-round))
        all-finished? (= num-games-in-this-round num-games-finished)
        one-game-left? (= num-games-in-this-round (inc num-games-finished))
        final-game-in-this-round (when one-game-left?
                                   (first (filter #(not= (:status (second %)) finished-status)
                                                  games-only-in-this-round)))]
    [:div.swiss-panel-container
      (when prev-round-finished?
        (list
          [:h3 (str "Swiss Round #" swiss-round " Matchups")]
          [:ul.matchups
            (map (partial Matchup prev-round-games) (partition 2 prev-round-results))]))

      (when-not (zero? num-games-finished)
        (list
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
                   "found on the \"Swiss Round " next-swiss-round "\" tab.")])))]))

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

(defn- on-change-team-dropdown [game-id team-key js-evt]
  (let [team-id (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games game-id team-key] team-id)))

;; TODO: put the team record in here
(rum/defc TeamOption < rum/static
  [[team-id team]]
  [:option {:value (name team-id)}
    (:name team)])

(defn- compare-team-name [[teamA-id teamA] [teamB-id teamB]]
  (compare (:name teamA) (:name teamB)))

;; TODO: consider using a better team input method than <select>
(rum/defc TeamSelect < rum/static
  [teams game-id game team-key]
  (let [sorted-teams (sort compare-team-name teams)]
    [:select.team-select
      {:on-change (partial on-change-team-dropdown game-id team-key)
       :value (team-key game)}
      [:option {:value ""} "-- Select a Team --"]
      (map TeamOption sorted-teams)]))

;; TODO: prevent them from picking the same team for teamA and teamB

(def date-format "YYYY-MM-DD HHmm")

(defn- format-start-time [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "ddd, DD MMM YYYY, h:mma")))

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
  (when (= status-val finished-status)
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
   finished-status "Finished"})

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

;; TODO: do not allow teamA and teamB to be the same in a single game
(rum/defc GameRow < rum/static
  [teams [game-id game]]
  (let [{:keys [start-time teamA-id teamB-id]} game
        status (get game :status scheduled-status)
        teamA (get teams (keyword teamA-id) false)
        teamB (get teams (keyword teamB-id) false)
        both-teams-selected? (and teamA teamB)
        scoreA (:scoreA game 0)
        scoreB (:scoreB game 0)
        any-points? (or (pos? scoreA) (pos? scoreB))
        game-name (:name game)
        finished? (= status finished-status)
        scorable? (or finished?
                      (= status in-progress-status))]
    [:div.game-row-container
      [:table {:data-game-id (name game-id)}
        [:tbody
          [:tr
            [:td.label-cell "Name"]
            [:td.name-input-cell
              [:input {:on-change (partial on-change-game-name game-id)
                       :type "text"
                       :value game-name}]]
            [:td.label-cell "Start Time"]
            [:td [:input.time-input
                   {:on-change (partial on-change-start-time game-id)
                    :placeholder "YYYY-MM-DD HHMM"
                    :type "text"
                    :value start-time}]]]
          [:tr
            [:td.label-cell "Team A"]
            [:td (if-not scorable?
                   (TeamSelect teams game-id game :teamA-id)
                   (:name teamA))]
            [:td.label-cell (when both-teams-selected? "Score A")]
            [:td.score-cell (when both-teams-selected? (ScoreInput game-id scoreA :scoreA finished?))]]
          [:tr
            [:td.label-cell "Team B"]
            [:td (if-not scorable?
                   (TeamSelect teams game-id game :teamB-id)
                   (:name teamB))]
            [:td.label-cell (when both-teams-selected? "Score B")]
            [:td.score-cell (when both-teams-selected? (ScoreInput game-id scoreB :scoreB finished?))]]
          [:tr
            [:td.label-cell "Status"]
            [:td {:col-span "3"}
              (StatusInput game-id scheduled-status status any-points?)
              (StatusInput game-id in-progress-status status (not both-teams-selected?))
              (StatusInput game-id finished-status status (not both-teams-selected?))]]]]]))

;;------------------------------------------------------------------------------
;; Game Groups Tabs
;;------------------------------------------------------------------------------

(defn- click-game-tab [tab-id js-evt]
  (neutralize-event js-evt)
  (swap! page-state assoc :tab-id tab-id))

(rum/defc GamesTab < rum/static
  [txt tab-id current-tab]
  [:div {:class (str "htab" (when (= tab-id current-tab) " active"))
         :on-click (partial click-game-tab tab-id)
         :on-touch-start (partial click-game-tab tab-id)}
    txt])

(rum/defc GamesTabs < rum/static
  [current-tab]
  [:div.filters-container
    (GamesTab "Swiss Round 1" "swiss-round-1" current-tab)
    (GamesTab "Swiss Round 2" "swiss-round-2" current-tab)
    (GamesTab "Swiss Round 3" "swiss-round-3" current-tab)
    (GamesTab "Swiss Round 4" "swiss-round-4" current-tab)
    (GamesTab "Bracket Play" "bracket-play" current-tab)])
    ;; TODO: split up bracket play from 9th / 11th games?
    ;; TODO: add a Results tab here

;; TODO: this is a quick hack; move this to a data structure
(defn- filter-games [all-games filter-val]
  (filter #(= (:group (second %)) filter-val) all-games))

(rum/defc GamesPage < rum/static
  [{:keys [teams games tab-id simulated-scoreA simulated-scoreB]}]
  (let [filtered-games (filter-games games tab-id)
        sorted-games (sort compare-games filtered-games)
        swiss-round (condp = tab-id
                      "swiss-round-1" 1
                      "swiss-round-2" 2
                      "swiss-round-3" 3
                      "swiss-round-4" 4
                      false)]
    [:article.games-container
      (GamesTabs tab-id)
      [:div.flex-container
        [:div.left (map (partial GameRow teams) sorted-games)]
        [:div.right (when swiss-round (SwissPanel teams games swiss-round simulated-scoreA simulated-scoreB))]]]))

;;------------------------------------------------------------------------------
;; Games Body
;;------------------------------------------------------------------------------

(rum/defc GameRow2 < rum/static
  [{:keys [name start-time]}]
  [:div (str start-time " - " name)])

(rum/defc GamesList < rum/static
  [title games]
  [:section
    [:h2.title-eef62 title]
    [:div.flex-052ba
      [:div.col-beeb5
        (map GameRow2 (sort-by :start-time games))]
      [:div.col-beeb5
        "TODO: swiss results table here"]]])

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
;; Loading Modal
;;------------------------------------------------------------------------------

(rum/defc LoadingModal < rum/static
  [txt]
  [:div
    [:div.modal-layer-20e76]
    [:div.saving-modal-58ebc
      "TODO: spinny icon here"
      txt]])

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

(rum/defc Menu < rum/static
  []
  [:div
    [:div.modal-layer-20e76 {:on-click close-modal}]
    [:div.modal-body-41add
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-1")} "Swiss Round 1"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-2")} "Swiss Round 2"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-3")} "Swiss Round 3"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "swiss-round-4")} "Swiss Round 4"]
      [:div.menu-link-14aa1 {:on-click (partial click-menu-link "bracket-play")} "Bracket Play"]
      [:div.menu-link-14aa1 {:on-click click-sign-out} "Sign out"]]])

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
    [:div.top-bar
      [:div.left title]
      [:div.right
        [:button
          {:on-click click-menu-btn}
          "Menu"]]]])
      ; [:div.right
      ;   {:on-click click-sign-out
      ;    :on-touch-start click-sign-out}
      ;   [:span.sign-out-txt "Sign Out"]
      ;   (SVGIcon "signout-7f21d" "signOut")]]])

;;------------------------------------------------------------------------------
;; Admin App
;;------------------------------------------------------------------------------

(def group-id->group-name
  {"swiss-round-1" "Swiss Round 1"
   "swiss-round-2" "Swiss Round 2"
   "swiss-round-3" "Swiss Round 3"
   "swiss-round-4" "Swiss Round 4"
   "bracket-play" "Bracket Play"})

(rum/defc AdminApp < rum/static
  [{:keys [active-page
           games
           loading-modal-showing?
           loading-modal-txt
           menu-showing?
           title]}]
  (let [active-games (get-games-in-group games active-page)]
    [:div.admin-container
      (Header title)
      (GamesList (get group-id->group-name active-page) active-games)
      (Footer)
      (when menu-showing?
        (Menu))
      (when loading-modal-showing?
        (LoadingModal loading-modal-txt))]))

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

(add-watch page-state :main on-change-page-state)

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
