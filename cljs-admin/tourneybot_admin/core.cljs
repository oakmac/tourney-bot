(ns tourneybot-admin.core
  (:require
    cljsjs.marked
    cljsjs.moment
    [clojure.string :refer [blank? lower-case]]
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-ajax-text
                             fetch-json-as-cljs tourney-bot-url]]
    [rum.core :as rum]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def info-tab "INFO-TAB")
(def teams-tab "TEAMS-TAB")
(def games-tab "GAMES-TAB")
(def swiss-tab "SWISS-TAB")
(def tab-values #{info-tab teams-tab games-tab swiss-tab})

(def tournament-state-url "tournament.json")
(def info-page-url "info.md")

(def scheduled-status "scheduled")
(def in-progress-status "in_progress")
(def finished-status "finished")
(def game-statuses #{scheduled-status in-progress-status finished-status})

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:tab info-tab})

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
;; Game Input
;;------------------------------------------------------------------------------

(defn- prevent-default [js-evt]
  (.preventDefault js-evt))

(defn- click-add-point [game-id score-key js-evt]
  (prevent-default js-evt)
  (swap! page-state update-in [:games game-id score-key] inc)

  ;; games with any points cannot have status "scheduled"
  (when (= "scheduled" (get-in @page-state [:games game-id :status]))
    (swap! page-state assoc-in [:games game-id :status] "in_progress")))

(defn- click-remove-point [game-id score-key js-evt]
  (prevent-default js-evt)
  (swap! page-state update-in [:games game-id score-key] dec))

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

(defn- click-back-btn []
  ;; TODO: write this
  nil)

(rum/defc GameInput < rum/static
  [game-id game]
  (let [teamA-id (keyword (:teamA-id game))
        teamB-id (keyword (:teamB-id game))
        teamA (get-in @page-state [:teams teamA-id])
        teamB (get-in @page-state [:teams teamB-id])
        current-status (:status game)]
    [:div.game-input-container
      [:div.teams
        [:div.team-name (:name teamA)]
        [:div.vs "vs"]
        [:div.team-name (:name teamB)]]
      [:div.scores
        [:div.score
          (UpBtn game-id :scoreA)
          [:div.big-score (:scoreA game)]
          (if (zero? (:scoreA game))
            (DisabledDownBtn)
            (DownBtn game-id :scoreA))]
        ;; NOTE: this empty element just used as a spacer
        [:div.vs ""]
        [:div.score
          (UpBtn game-id :scoreB)
          [:div.big-score (:scoreB game)]
          (if (zero? (:scoreB game))
            (DisabledDownBtn)
            (DownBtn game-id :scoreB))]]
      [:div.status
        (if (or (> (:scoreA game) 0)
                (> (:scoreB game) 0))
          (DisabledStatusTab "Scheduled")
          (StatusTab "Scheduled" "scheduled" current-status game-id))
        (StatusTab "In Progress" "in_progress" current-status game-id)
        (StatusTab "Finished" "finished" current-status game-id)]
      [:div.back-btn
        {:on-click click-back-btn
         :on-touch-start click-back-btn}
        "Go Back"]]))

;;------------------------------------------------------------------------------
;; Games Page
;;------------------------------------------------------------------------------

(rum/defc GameRow < rum/static
  [[game-id game]]
  (let [games-vec nil]
    [:div.game-row
      (name game-id)]))

(defn- compare-games [a b]
  (compare (-> a second :start-time)
           (-> b second :start-time)))

(rum/defc GamesPage < rum/static
  [games-map]
  (let [games-vec (vec games-map)
        sorted-games (sort compare-games games-vec)]
    [:article.games-container
      (map GameRow sorted-games)]))

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
  [:div.team-input-container
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

(rum/defc TeamsPage < rum/static
  [teams-map]
  (let [teams-vec (vec teams-map)
        sorted-teams (sort #(compare (first %1) (first %2)) teams-vec)]
    [:article.teams-container
      ;; TODO: finish this idea
      ; [:input {:type "button"
      ;          :on-click click-new-team-btn
      ;          :value "New Team"}]
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

;; TODO: link between admin and client side

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
  (let [current-tab (:tab state)]
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
        (TeamsPage (:teams state))

        games-tab
        ;;(GamesPage (:games state))
        (GameInput :game13 (get-in state [:games :game13]))

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
