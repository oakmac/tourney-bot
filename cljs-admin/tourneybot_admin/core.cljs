(ns tourneybot-admin.core
  (:require
    cljsjs.marked
    cljsjs.moment
    [clojure.string :refer [blank? lower-case]]
    [tourneybot.data :refer [scheduled-status in-progress-status finished-status
                             game-statuses
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

(def info-tab "INFO-TAB")
(def teams-tab "TEAMS-TAB")
(def games-tab "GAMES-TAB")
(def edit-game-tab "EDIT-GAME-TAB")
(def swiss-tab "SWISS-TAB")
(def tab-values #{info-tab teams-tab games-tab edit-game-tab swiss-tab})

(def tournament-state-url "../tournament.json")
(def info-page-url "../info.md")

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:editing-game-id nil
   :hide-finished-games? true
   :tab info-tab})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

(defn- valid-page-state?
  "Some simple predicates to make sure the state is valid."
  [new-state]
  (and (map? new-state)
       (contains? tab-values (:tab new-state))))

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

(rum/defc SwissPage < rum/static
  [state]
  [:article.swiss-container
    "TODO: swiss rounds page"])

;;------------------------------------------------------------------------------
;; Scores Input
;;------------------------------------------------------------------------------

;; TODO: do not allow a game to reach "Finished" state if the scores are equal
;;       and ties are not allowed

(defn- prevent-default [js-evt]
  (.preventDefault js-evt))

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
  (swap! page-state assoc :tab games-tab))

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
        [:i.fa.fa-hand-o-left] " Go Back"]]))

;;------------------------------------------------------------------------------
;; Games Page
;;------------------------------------------------------------------------------

(defn- click-edit-game [game-id js-evt]
  (prevent-default js-evt)
  (swap! page-state assoc :tab edit-game-tab
                          :editing-game-id game-id))

(defn- on-change-game-name [game-id js-evt]
  (let [new-name (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games game-id :name] new-name)))

(defn- on-change-start-time [game-id js-evt]
  (let [new-time (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc-in [:games game-id :start-time] new-time)))

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

(rum/defc GamesPage < rum/static
  [teams games hide-finished-games?]
  (let [games (if hide-finished-games?
                (remove #(= (:status (second %)) finished-status) games)
                games)
        sorted-games (sort compare-games games)]
    [:article.games-container
      [:label.top-option
        {:on-click toggle-hide-finished-games
         :on-touch-start toggle-hide-finished-games}
        (if hide-finished-games?
          [:i.fa.fa-check-square-o]
          [:i.fa.fa-square-o])
        "Hide finished games"]
      (map (partial GameRow teams) sorted-games)]))

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
;; Navigation Tabs
;;------------------------------------------------------------------------------

(defn- click-tab [tab-id js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :tab tab-id))

(rum/defc Tab < rum/static
  [name tab-id current-tab]
  [:li {:class (if (= tab-id current-tab) "active" "")
        :on-click (partial click-tab tab-id)
        :on-touch-start (partial click-tab tab-id)}
    [:a {:href "#"
         :on-click (fn [js-evt] (.preventDefault js-evt))}
      name]])

(rum/defc NavTabs < rum/static
  [current-tab]
  [:nav
    [:ul
      (Tab "Info" info-tab current-tab)
      (Tab "Teams" teams-tab current-tab)
      (Tab "Games" games-tab current-tab)
      (Tab "Swiss Rounds" swiss-tab current-tab)]])

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
  (let [current-tab (:tab state)
        editing-game-id (keyword (:editing-game-id state))
        teams (:teams state)
        games (:games state)
        hide-finished-games? (:hide-finished-games? state)]
    [:div.admin-container
      [:header
        [:div.top-bar
          [:div.left (:title state)]
          [:div.right "Admin"]]
        (NavTabs current-tab)]
      (condp = current-tab
        info-tab
        (InfoPage state)

        teams-tab
        (TeamsPage teams)

        games-tab
        (GamesPage teams games hide-finished-games?)

        edit-game-tab
        (EditGamePage teams editing-game-id (get-in state [:games editing-game-id]))

        swiss-tab
        (SwissPage state)

        ;; NOTE: this should never happen
        [:div "Error: invalid tab"])
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
