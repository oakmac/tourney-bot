(ns tourneybot-admin.core
  (:require
    cljsjs.marked
    cljsjs.moment
    [clojure.string :refer [blank? lower-case replace]]
    [tourneybot.data :refer [scheduled-status in-progress-status finished-status game-statuses
                             games->results
                             ensure-tournament-state]]
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-ajax-text
                             fetch-json-as-cljs tourney-bot-url]]
    [tourneybot-admin.api :refer [save-tournament-state!]]
    [rum.core :as rum]))

;; TODO: set up some logic such that when a quarterfinals game is finished, it
;;       automatically seeds the team into the next bracket game

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def info-page "INFO-PAGE")
(def teams-page "TEAMS-PAGE")
(def games-page "GAMES-PAGE")
(def edit-game-page "EDIT-GAME-PAGE")
(def swiss-page "SWISS-PAGE")
(def page-values #{info-page teams-page games-page edit-game-page swiss-page})

(def tournament-state-url "../tournament.json")
(def info-page-url "../info.md")

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:editing-game-id nil
   :games-filter-tab "all-games"
   :hide-finished-games? true
   :page info-page
   :swiss-filter-tab "swiss-round-1"})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

(defn- valid-page-state?
  "Some simple predicates to make sure the state is valid."
  [new-state]
  (and (map? new-state)
       (contains? page-values (:page new-state))))

(set-validator! page-state valid-page-state?)

;;------------------------------------------------------------------------------
;; Update the tournament state
;;------------------------------------------------------------------------------

(def tournament-state-keys
  "These keys represent the tournament state and should always be reflected in
   tournament.json."
  #{:title
    :tiesAllowed
    :divisions
    :fields
    :teams
    :games})

;; TODO: need to figure out if we want this to happen on every update (ie: add-watch)
;;       or trigger it manually from a "Save" button or similar
;;       Another option would be to poll for changes every N seconds and only
;;       send an update if anything has changed.

(defn- upload-tournament-state!
  "Update "
  [_kwd _the-atom _old-state new-state])

;;(add-watch page-state :upload upload-tournament-state!)

;;------------------------------------------------------------------------------
;; Save UI-specific app state to localStorage
;;------------------------------------------------------------------------------

(def ls-key "admin-page-state")

;; load any existing client state on startup
(when-let [state-string (js/window.localStorage.getItem ls-key)]
  (let [js-state (try (js/JSON.parse state-string)
                   (catch js/Error _error nil))]
    (when (object? js-state)
      (let [clj-state (js->clj js-state :keywordize-keys true)]
        (swap! page-state merge clj-state)))))

(def ui-only-page-state-keys (keys initial-page-state))

(defn- save-client-state [_kwd _the-atom _old-state new-state]
  (let [ui-only-state (select-keys new-state ui-only-page-state-keys)
        js-state (clj->js ui-only-state)
        js-state-string (js/JSON.stringify js-state)]
    (js/window.localStorage.setItem ls-key js-state-string)))

(add-watch page-state :client-state save-client-state)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

(def polling-rate-ms 5000)
(def title-set? (atom false))

(defn- fetch-tourney-state-success [new-state]
  ;; set the title tag on the first state load
  (when-not @title-set?
    (aset js/document "title" (:title new-state))
    (reset! title-set? true))
  ;; merge the tournament state with the page state
  (swap! page-state merge new-state))

(defn- fetch-tourney-state! []
  (fetch-json-as-cljs tournament-state-url fetch-tourney-state-success))

;; kick off the initial state fetch
(fetch-tourney-state!)

;; TODO: I'm not sure we want to poll for tournament state here
;;       the admin should always be "sending" the state, not "receiving" it
;;       maybe once a minute in case somehow they get out of sync?

;; begin the polling
;;(js/setInterval fetch-tourney-state! polling-rate-ms)

;;------------------------------------------------------------------------------
;; Misc
;;------------------------------------------------------------------------------

(defn- prevent-default [js-evt]
  (.preventDefault js-evt))

;;------------------------------------------------------------------------------
;; Fetch Info Page
;;------------------------------------------------------------------------------

; (def five-minutes (* 5 60 1000))
; (def info-polling-ms five-minutes)
;
; (defn- fetch-info-page-success [info-markdown]
;   (let [info-html (js/marked info-markdown)
;         info-el (by-id "infoContainer")]
;     (when (and info-html info-el)
;       (aset info-el "innerHTML" info-html))))
;
; (defn- fetch-info-page! []
;   (fetch-ajax-text info-page-url fetch-info-page-success))
;
; ;; fetch the info page markdown on load
; (fetch-info-page!)
;
; ;; poll for updates every 5 minutes
; (js/setInterval fetch-info-page! info-polling-ms)

;;------------------------------------------------------------------------------
;; Swiss Rounds Page
;;------------------------------------------------------------------------------

;; TODO: this can be replaced using a set of sets:
;; #{ #{teamA teamB}
;;    #{teamA teamB}
;;    ...}
(defn- teams-already-played? [teamA-id teamB-id all-games]
  (let [teamA-id (name teamA-id)
        teamB-id (name teamB-id)
        games-list (vals all-games)
        games-where-teams-played-each-other
          (filter #(or (and (= teamA-id (:teamA-id %)) (= teamB-id (:teamB-id %)))
                       (and (= teamB-id (:teamA-id %)) (= teamA-id (:teamB-id %))))
                  games-list)]
    (if (empty? games-where-teams-played-each-other)
      false
      (first games-where-teams-played-each-other))))

(defn- is-swiss-game? [g]
  (integer? (:swiss-round g)))

;; TODO: take "ties-allowed?" into account here
(rum/defc SwissResultsRow < rum/static
  [{:keys [team-name games-won games-lost games-tied
           points-won points-lost points-diff
           victory-points]}]
  [:tr
    [:td.name team-name]
    [:td (str games-won "-" games-lost "-" games-tied)]
    [:td (str "+" points-won ",  "
              "-" (js/Math.abs points-lost) ", "
              (if (neg? points-diff)
                points-diff
                (str "+" points-diff)))]
    [:td victory-points]])

(rum/defc SwissResultsTHead < rum/static
  []
  [:thead
    [:tr
      [:th.name {:style {:width "40%"}} "Name"]
      [:th "Record"]
      [:th "Points"]
      [:th "Victory" [:br] "Points"]]])

(rum/defc SwissResultsTable < rum/static
  [results]
  [:table.small-results-tbl
    (SwissResultsTHead)
    [:tbody
      (map SwissResultsRow results)]])

(rum/defc Matchup < rum/static
  [games [resultA resultB]]
  (let [teamA-id (:team-id resultA)
        teamB-id (:team-id resultB)
        recordA (str (:games-won resultA) "-" (:games-lost resultA) "-" (:games-tied resultA))
        recordB (str (:games-won resultB) "-" (:games-lost resultB) "-" (:games-tied resultB))
        already-played? (teams-already-played? teamA-id teamB-id games)]
    [:li.matchup-row
      [:span.team (str (:team-name resultA) " (" recordA ")")]
      [:span.vs "vs"]
      [:span.team (str (:team-name resultB) " (" recordB ")")]
      (when already-played?
        [:span.already-played (str "Whoops! These two teams already played in " (:name already-played?))])]))

(rum/defc SwissRound < rum/static
  [teams all-games swiss-round]
  (let [;; get all the games for this swiss round and below
        games-to-look-at (filter #(and (is-swiss-game? (second %))
                                       (<= (:swiss-round (second %)) swiss-round))
                                 all-games)
        ;; calculate the results for this round
        results (games->results teams games-to-look-at)
        ;; get all the games in just this swiss round
        games-in-this-round (filter #(and (is-swiss-game? (second %))
                                          (= (:swiss-round (second %)) swiss-round))
                                    games-to-look-at)
        num-games-in-this-round (count games-in-this-round)
        ;; are all the games in this swiss round finished?
        num-games-finished (count (filter #(= (:status (second %)) finished-status)
                                          games-in-this-round))
        all-finished? (= num-games-in-this-round num-games-finished)]
    [:div.swiss-round-container
      [:h2 (str "Swiss Round #" swiss-round)]
      [:p.info
        (cond
          all-finished?
          (str "All " num-games-in-this-round " games in Swiss Round #" swiss-round " have been played.")

          (zero? num-games-finished)
          (str "Swiss Round #" swiss-round " has not started yet.")

          :else
          (str num-games-finished " out of " num-games-in-this-round " rounds have been played in Swiss Round #" swiss-round))]
      (when-not (zero? num-games-finished)
        (list
          [:h3 (str "Swiss Round #" swiss-round " Results")]
          (SwissResultsTable results)
          (when (and all-finished?
                     (not (= swiss-round 4))) ;; NOTE: temporary hack
            (list
              [:h3 (str "Matchups for Swiss Round #" (inc swiss-round))]
              (let [matchups (partition 2 results)]
                [:ul
                  (map (partial Matchup games-to-look-at) matchups)])))))]))

(defn- click-swiss-filter-tab [tab-id js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc :swiss-filter-tab tab-id))

(rum/defc SwissFilterTab < rum/static
  [txt tab-id current-tab]
  [:div {:class (str "htab" (when (= tab-id current-tab) " active"))
         :on-click (partial click-swiss-filter-tab tab-id)
         :on-touch-start (partial click-swiss-filter-tab tab-id)}
    txt])

(rum/defc SwissFilters < rum/static
  [current-tab]
  [:div.filters-container
    (SwissFilterTab "Swiss Round 1" "swiss-round-1" current-tab)
    (SwissFilterTab "Swiss Round 2" "swiss-round-2" current-tab)
    (SwissFilterTab "Swiss Round 3" "swiss-round-3" current-tab)
    (SwissFilterTab "Swiss Round 4" "swiss-round-4" current-tab)])

(rum/defc SwissPage < rum/static
  [teams games current-tab]
  (let [swiss-round (int (replace current-tab "swiss-round-" ""))]
    [:article.swiss-container
      (SwissFilters current-tab)
      (SwissRound teams games swiss-round)]))

;;------------------------------------------------------------------------------
;; Scores Input
;;------------------------------------------------------------------------------

;; TODO: do not allow a game to reach "Finished" state if the scores are equal
;;       and ties are not allowed

(defn- click-add-point [game-id score-key js-evt]
  (prevent-default js-evt)
  (swap! page-state update-in [:games game-id score-key] inc)

  ;; games with any points cannot have status "scheduled"
  (when (= scheduled-status (get-in @page-state [:games game-id :status] scheduled-status))
    (swap! page-state assoc-in [:games game-id :status] "in_progress")))

(defn- click-remove-point [game-id score-key js-evt]
  (prevent-default js-evt)
  (swap! page-state update-in [:games game-id score-key] dec))

(rum/defc InvisibleBtn < rum/static
  []
  [:div.button.up {:style {:visibility "hidden"}} "+1"])

(rum/defc UpBtn < rum/static
  [game-id score-key]
  [:div.button.up
    {:on-click (partial click-add-point game-id score-key)
     :on-touch-start (partial click-add-point game-id score-key)}
    "+1"])

(rum/defc DownBtn < rum/static
  [game-id score-key]
  [:div.button.down
    {:on-click (partial click-remove-point game-id score-key)
     :on-touch-start (partial click-remove-point game-id score-key)}
    "-1"])

(rum/defc DisabledDownBtn < rum/static
  []
  [:div.button.down.disabled
    {:on-click prevent-default
     :on-touch-start prevent-default}
    "-1"])

(defn- click-status-tab [game-id new-status js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc-in [:games game-id :status] new-status))

(rum/defc StatusTab < rum/static
  [txt status current-status game-id]
  [:div {:class (str "tab" (when (= current-status status) " active"))
         :on-click (partial click-status-tab game-id status)
         :on-touch-start (partial click-status-tab game-id status)}
    txt])

(rum/defc DisabledStatusTab < rum/static
  [txt]
  [:div.tab.disabled
    {:on-click prevent-default
     :on-touch-start prevent-default}
    txt])

(defn- click-back-btn [js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc :page games-page))

;; TODO: highlight the winning team in yellow
;;       and include a "final score" note

(rum/defc ScoreInput < rum/static
  [game-id game score-key finished?]
  (let [current-score (get game score-key 0)]
    [:div.score
      (if finished?
        (InvisibleBtn)
        (UpBtn game-id score-key))
      [:div.big-score current-score]
      (if finished?
        (InvisibleBtn)
        (if (zero? current-score)
          (DisabledDownBtn)
          (DownBtn game-id score-key)))]))

(rum/defc EditGamePage < rum/static
  [teams game-id game]
  (let [teamA-id (keyword (:teamA-id game))
        teamB-id (keyword (:teamB-id game))
        teamA (get teams teamA-id)
        teamB (get teams teamB-id)
        current-status (:status game scheduled-status)
        finished? (= current-status finished-status)]
    [:article.game-input-container
      [:div.teams
        [:div.team-name (:name teamA)]
        [:div.vs "vs"]
        [:div.team-name (:name teamB)]]
      [:div.scores
        (ScoreInput game-id game :scoreA finished?)
        [:div.vs ""] ;; NOTE: this empty element just used as a spacer
        (ScoreInput game-id game :scoreB finished?)]
      [:div.status
        (if (or (> (:scoreA game) 0)
                (> (:scoreB game) 0))
          (DisabledStatusTab "Scheduled")
          (StatusTab "Scheduled" scheduled-status current-status game-id))
        (StatusTab "In Progress" in-progress-status current-status game-id)
        (StatusTab "Finished" finished-status current-status game-id)]
      [:div.back-btn
        {:on-click click-back-btn
         :on-touch-start click-back-btn}
        [:i.fa.fa-arrow-left] " Go Back"]]))

;;------------------------------------------------------------------------------
;; Games Page
;;------------------------------------------------------------------------------

(defn- click-edit-game [game-id js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc :page edit-game-page
                          :editing-game-id game-id))

(defn- on-change-game-name [game-id js-evt]
  (let [new-name (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games (keyword game-id) :name new-name])))

(defn- on-change-start-time [game-id js-evt]
  (let [new-time (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games (keyword game-id) :start-time new-time])))

;; TODO: need an on-blur function to make sure the time is formatted properly

(defn- on-change-team-dropdown [game-id team-key js-evt]
  (let [team-id (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games game-id team-key] team-id)))

(rum/defc TeamOption < rum/static
  [[team-id team]]
  [:option {:value (name team-id)}
    (:name team)])

(defn- compare-team-name [[teamA-id teamA] [teamB-id teamB]]
  (compare (:name teamA) (:name teamB)))

;; TODO: use a faster component for team selection than a <select>
(rum/defc TeamSelect < rum/static
  [teams game-id game team-key]
  (let [sorted-teams (sort compare-team-name teams)]
    [:select {:on-change (partial on-change-team-dropdown game-id team-key)
              :value (team-key game)}
      [:option {:value ""} "-- Select a Team --"]
      (map TeamOption sorted-teams)]))

;; TODO: prevent them from picking the same team for teamA and teamB

(rum/defc ScheduledGameRow < rum/static
  [teams game-id game]
  (let [start-time (:start-time game)]
    [:div.admin-input-row.games
      [:div.small-id (name game-id)]
      [:div.row
        [:label "Name"]
        [:input {:on-change (partial on-change-game-name game-id)
                 :type "text"
                 :value (:name game)}]]
      [:div.row
        [:label "Start Time"]
        [:input {:on-change (partial on-change-start-time game-id)
                 :placeholder "YYYY-MM-DD HHMM"
                 :type "text"
                 :value start-time}]]
      [:div.row
        [:label "Team A"]
        (TeamSelect teams game-id game :teamA-id)]
      [:div.row
        [:label "Team B"]
        (TeamSelect teams game-id game :teamB-id)]
      ;; only show the "Edit Scores" button when both teams have been selected
      (when (and (get teams (keyword (:teamA-id game)) false)
                 (get teams (keyword (:teamB-id game)) false))
        [:div.edit-scores-btn
          {:on-click (partial click-edit-game game-id)
           :on-touch-start (partial click-edit-game game-id)}
          "Edit Scores"])]))

(def date-format "YYYY-MM-DD HHmm")

(defn- format-start-time [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "ddd, DD MMM YYYY, h:mma")))

(rum/defc NonScheduledGameRow < rum/static
  [teams game-id game]
  (let [teamA (get teams (keyword (:teamA-id game)))
        teamB (get teams (keyword (:teamB-id game)))
        scoreA (:scoreA game)
        scoreB (:scoreB game)
        start-time (:start-time game)
        status (:status game)]
    [:div {:class (str "admin-input-row" (when (= status in-progress-status) " in-progress"))}
      [:div.small-id (name game-id)]
      [:div.row
        [:label.muted "Name"]
        [:input {:on-change (partial on-change-game-name game-id)
                 :type "text"
                 :value (:name game)}]]
      [:div.row
        [:label.muted "Time"]
        [:span (format-start-time start-time)]]
      [:div.row
        [:label.muted "Team A"]
        [:span (str (:name teamA) " (" scoreA ")")]]
      [:div.row
        [:label.muted "Team B"]
        [:span (str (:name teamB) " (" scoreB ")")]]
      [:div.row
        [:label.muted "Status"]
        [:span (if (= status in-progress-status)
                 "In Progress"
                 "Finished")]]
      [:div.edit-scores-btn
        {:on-click (partial click-edit-game game-id)
         :on-touch-start (partial click-edit-game game-id)}
        "Edit Scores"]]))

(rum/defc GameRow < rum/static
  [teams [game-id game]]
  (if (= (:status game scheduled-status) scheduled-status)
    (ScheduledGameRow teams game-id game)
    (NonScheduledGameRow teams game-id game)))

(defn- toggle-hide-finished-games [js-evt]
  (prevent-default js-evt)
  (swap! page-state update-in [:hide-finished-games?] not))

(defn- compare-games [a b]
  (compare (-> a second :start-time)
           (-> b second :start-time)))

; this is the old Gamespage
; (rum/defc GamesPage < rum/static
;  [teams games hide-finished-games?]
;  (let [games (if hide-finished-games?
;                (remove #(= (:status (second %)) finished-status) games)
;                games)
;        sorted-games (sort compare-games games)]
;    [:article.games-container
;      [:label.top-option
;        {:on-click toggle-hide-finished-games
;         :on-touch-start toggle-hide-finished-games}
;        (if hide-finished-games?
;          [:i.fa.fa-check-square-o]
;          [:i.fa.fa-square-o])
;        "Hide finished games"]
;      (map (partial GameRow teams) sorted-games)]))
;




(defn- on-change-score [game-id score-key js-evt]
  (let [new-score (int (aget js-evt "currentTarget" "value"))
        game-id (keyword game-id)]
    (swap! page-state assoc-in [:games game-id score-key] new-score)

    ;; games with any points cannot have status "scheduled"
    (when (= scheduled-status (get-in @page-state [:games game-id :status] scheduled-status))
      (swap! page-state assoc-in [:games game-id :status] in-progress-status))))

(defn- on-change-status [game-id status-val js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc-in [:games (keyword game-id) :status] status-val))

(rum/defc ScoreInput2 < rum/static
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
(rum/defc GameRow2 < rum/static
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
      [:h3 (name game-id)]
      [:table
        [:tbody
          [:tr
            [:td.label-cell "Name"]
            [:td.name-input-cell
              [:input {:on-change (partial on-change-game-name game-id)
                       :type "text"
                       :value game-name}]]
            [:td.label-cell "Start Time"]
            [:td [:input {:on-change (partial on-change-start-time game-id)
                          :placeholder "YYYY-MM-DD HHMM"
                          :type "text"
                          :value start-time}]]]
          [:tr
            [:td.label-cell "Team A"]
            [:td (if-not scorable?
                   (TeamSelect teams game-id game :teamA-id)
                   (:name teamA))]
            [:td.label-cell (when both-teams-selected? "Score A")]
            [:td.score-cell (when both-teams-selected? (ScoreInput2 game-id scoreA :scoreA finished?))]]
          [:tr
            [:td.label-cell "Team B"]
            [:td (if-not scorable?
                   (TeamSelect teams game-id game :teamB-id)
                   (:name teamB))]
            [:td.label-cell (when both-teams-selected? "Score B")]
            [:td.score-cell (when both-teams-selected? (ScoreInput2 game-id scoreB :scoreB finished?))]]
          [:tr
            [:td.label-cell "Status"]
            [:td {:col-span "3"}
              (StatusInput game-id scheduled-status status any-points?)
              (StatusInput game-id in-progress-status status (not both-teams-selected?))
              (StatusInput game-id finished-status status (not both-teams-selected?))]]]]]))

(defn- click-games-filter-tab [tab-id js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc :games-filter-tab tab-id))

(rum/defc GameFilterTab < rum/static
  [txt tab-id current-tab]
  [:div {:class (str "htab" (when (= tab-id current-tab) " active"))
         :on-click (partial click-games-filter-tab tab-id)
         :on-touch-start (partial click-games-filter-tab tab-id)}
    txt])

(rum/defc GamesFilters < rum/static
  [current-tab]
  [:div.filters-container
    (GameFilterTab "All Games" "all-games" current-tab)
    (GameFilterTab "Swiss Round 1" "swiss-round-1" current-tab)
    (GameFilterTab "Swiss Round 2" "swiss-round-2" current-tab)
    (GameFilterTab "Swiss Round 3" "swiss-round-3" current-tab)
    (GameFilterTab "Swiss Round 4" "swiss-round-4" current-tab)
    (GameFilterTab "Bracket Play" "bracket-play" current-tab)])

;; TODO: this is a quick hack; move this to a data structure
(defn- filter-games [all-games filter-val]
  (if (= filter-val "all-games")
    all-games
    (filter #(= (:group (second %)) filter-val) all-games)))

(rum/defc GamesPage < rum/static
  [teams games games-filter-tab hide-finished-games?]
  (let [games (filter-games games games-filter-tab)
        sorted-games (sort compare-games games)]
    [:article.games-container
      (GamesFilters games-filter-tab)
      (map (partial GameRow2 teams) sorted-games)]))

;;------------------------------------------------------------------------------
;; Teams Page
;;------------------------------------------------------------------------------

(defn- on-change-team-name [team-id js-evt]
  (let [new-name (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:teams team-id :name] new-name)))

(defn- on-change-team-captain [team-id js-evt]
  (let [new-name (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:teams team-id :captain] new-name)))

(rum/defc TeamInput < rum/static
  [[team-id team]]
  [:div.admin-input-row
    [:div.small-id (name team-id)]
    [:div.row
      [:label "Name"]
      [:input {:on-change (partial on-change-team-name team-id)
               :type "text"
               :value (:name team)}]]
    [:div.row
      [:label "Captain"]
      [:input {:on-change (partial on-change-team-captain team-id)
               :type "text"
               :value (:captain team)}]]])

;; TODO: allow them to create a team here

(rum/defc TeamsPage < rum/static
  [teams]
  (let [sorted-teams (sort #(compare (first %1) (first %2)) teams)]
    [:article.teams-container
      (map TeamInput sorted-teams)]))

;;------------------------------------------------------------------------------
;; Info Page
;;------------------------------------------------------------------------------

(rum/defc InfoPage < rum/static
  [state]
  [:article.info-container
    "TODO: info page"])

;;------------------------------------------------------------------------------
;; Nav Menu
;;------------------------------------------------------------------------------

(defn- click-page-link [page-id js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :page page-id))

(rum/defc PageLink < rum/static
  [name page-id current-page-id]
  [:li {:class (if (= page-id current-page-id) "active" "")
        :on-click (partial click-page-link page-id)
        :on-touch-start (partial click-page-link page-id)}
    [:a {:href "#"
         :on-click prevent-default}
      name]])

(rum/defc NavMenu < rum/static
  [current-page-id]
  [:nav
    [:ul.links
      (PageLink "Info" info-page current-page-id)
      (PageLink "Teams" teams-page current-page-id)
      (PageLink "Games" games-page current-page-id)
      (PageLink "Swiss Rounds" swiss-page current-page-id)]])

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
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc AdminApp < rum/static
  [state]
  (let [page (:page state)
        editing-game-id (keyword (:editing-game-id state))
        teams (:teams state)
        games (:games state)
        hide-finished-games? (:hide-finished-games? state)
        games-filter-tab (:games-filter-tab state)
        swiss-filter-tab (:swiss-filter-tab state)]
    [:div.admin-container
      [:header
        [:div.top-bar
          [:div.left (:title state)]]]
      (NavMenu (:page state))
      (condp = page
        info-page
        (InfoPage state)

        teams-page
        (TeamsPage teams)

        games-page
        (GamesPage teams games games-filter-tab hide-finished-games?)

        edit-game-page
        (EditGamePage teams editing-game-id (get-in state [:games editing-game-id]))

        swiss-page
        (SwissPage teams games swiss-filter-tab)

        ;; NOTE: this should never happen
        [:div "Error: invalid page"])
      (Footer)]))

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "adminContainer"))

(defn- on-change-page-state
  "Render the page on every state change."
  [_kwd _the-atom _old-state new-state]
  (rum/request-render
    (rum/mount (AdminApp new-state) app-container-el)))

(add-watch page-state :main on-change-page-state)

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

(defn- init!
  "Initialize the Admin page."
  []
  ;; trigger the initial render
  (swap! page-state identity))

(init!)
