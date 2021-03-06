(ns tourneybot-client.core
  (:require
    [cljsjs.marked]
    [cljsjs.moment]
    [clojure.string :refer [blank? lower-case]]
    [goog.functions :refer [once]]
    [rum.core :as rum]
    [tourneybot.data :refer [ensure-tournament-state
                             final-status
                             game-finished?
                             game-statuses
                             games->results
                             in-progress-status
                             scheduled-status]]
    [tourneybot.util :refer [atom-logger
                             by-id
                             fetch-ajax-text
                             fetch-json-as-cljs
                             game->date
                             index-key-fn-mixin
                             js-log
                             log
                             tourney-bot-url]]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def info-tab-id "INFO-TAB")
(def schedule-tab-id "SCHEDULE-TAB")
(def results-tab-id "RESULTS-TAB")
(def tab-values #{info-tab-id schedule-tab-id results-tab-id})

(def sort-on-name "SORT-BY-NAME")
(def sort-on-record "SORT-BY-RECORD")
(def sort-on-values #{sort-on-name sort-on-record})

(def in-dev-mode? (not= -1 (.indexOf js/document.location.href "devmode")))

(def info-page-url "info.md")
(def tournament-state-url
  (if in-dev-mode?
    "tournament.json"
    "api/latest.php"))

;; TODO: allow this to be overriden with a query param
(def refresh-rate-ms 5000)

;; TODO: this probably belongs in util or common namespace
(defn- game-scheduled? [g]
  (= scheduled-status (:status g)))

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:hide-finished-games? false
   :schedule-search-text ""
   :sort-results-by sort-on-name
   :tab info-tab-id})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

(defn- valid-page-state?
  "Some simple predicates to make sure the state is valid."
  [new-state]
  (and (map? new-state)
       (contains? tab-values (:tab new-state))
       (contains? sort-on-values (:sort-results-by new-state))))

(set-validator! page-state valid-page-state?)

;;------------------------------------------------------------------------------
;; Title
;;------------------------------------------------------------------------------

(defn- set-page-title [_kwd _the-atom old-state new-state]
  (let [old-title (:title old-state)
        new-title (:title new-state)]
    (when-not (= old-title new-title)
      (aset js/document "title" new-title))))

(add-watch page-state :title set-page-title)

;;------------------------------------------------------------------------------
;; Save UI-specific app state to localStorage
;;------------------------------------------------------------------------------

(def ls-key "client-page-state")

;; load any existing client state on startup
(try
  (let [state-string (js/window.localStorage.getItem ls-key)
        js-state (js/JSON.parse state-string)]
    (when (object? js-state)
      (let [clj-state (js->clj js-state :keywordize-keys true)]
        (swap! page-state merge clj-state))))
  (catch js/Error err nil))

(def ui-only-page-state-keys (keys initial-page-state))

(defn- save-client-state [_kwd _the-atom _old-state new-state]
  (let [ui-only-state (select-keys new-state ui-only-page-state-keys)
        js-state (clj->js ui-only-state)
        js-state-string (js/JSON.stringify js-state)]
    (try
      (js/window.localStorage.setItem ls-key js-state-string)
      (catch js/Error err nil))))

(add-watch page-state :client-state save-client-state)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

(defn- fetch-tourney-state-success [new-state]
  ;; merge the tournament state with the page state
  (swap! page-state merge new-state))

(defn- fetch-tourney-state! []
  (fetch-json-as-cljs tournament-state-url fetch-tourney-state-success))

;; begin polling for updates
(js/setInterval fetch-tourney-state! refresh-rate-ms)

;;------------------------------------------------------------------------------
;; Fetch Info Page
;;------------------------------------------------------------------------------

(def five-minutes (* 5 60 1000))
(def info-polling-ms five-minutes)

(defn- fetch-info-page-success [info-markdown]
  (let [info-html (js/marked info-markdown)
        info-el (by-id "infoContainer")]
    (when (and info-html info-el)
      (aset info-el "innerHTML" info-html))))

(defn- fetch-info-page! []
  (fetch-ajax-text info-page-url fetch-info-page-success))

;; poll for updates every 5 minutes
(js/setInterval fetch-info-page! info-polling-ms)

;;------------------------------------------------------------------------------
;; SVG Icon
;;------------------------------------------------------------------------------

(rum/defc SVGIcon < rum/static
  [svg-class icon-id]
  [:svg
    {:class svg-class
     :dangerouslySetInnerHTML
       {:__html (str "<use xlink:href='img/icons.svg#" icon-id "' />")}}])

;;------------------------------------------------------------------------------
;; Results Page
;;------------------------------------------------------------------------------

(rum/defc ResultsTableHeader < rum/static
  [ties-allowed?]
  [:thead
    [:tr
      [:th {:style {:width "4%"}} ""]
      [:th {:style {:text-align "left"}} "Team"]
      [:th {:style {:width "30%"}}
        [:div.games-header "Games"]
        [:div.points-header "Points"]]]])

(rum/defc TeamNameCell < rum/static
  [team-name captain]
  [:div.team-name-container
    [:div.team-name team-name]
    (when (and (string? captain)
               (not (blank? captain)))
      [:div.captain captain])])

(rum/defc RecordCell < rum/static
  [ties-allowed? {:keys [games-won games-lost games-tied
                         points-won points-lost points-diff]}]
  [:td.record-cell
    [:div.big-record
      [:span.big-num games-won]
      [:span.dash "-"]
      [:span.big-num games-lost]
      (when ties-allowed?
        (list [:span.dash {:key 1} "-"]
              [:span.big-num {:key 2} games-tied]))]
    [:div.small-points
      [:span.small-point (str "+" points-won)]
      [:span ", "]
      [:span.small-point (str "-" points-lost)]
      [:span ", "]
      [:span.small-point (if (neg? points-diff)
                           points-diff
                           (str "+" points-diff))]]])

(rum/defc ResultRow < (merge rum/static index-key-fn-mixin)
  [idx result ties-allowed?]
  (let [no-games-played? (zero? (:games-played result))]
    [:tr
      [:td.place (when-not no-games-played? (str "#" (:place result)))]
      [:td.team-name (TeamNameCell (:team-name result) (:team-captain result))]
      (if no-games-played?
        [:td.record-cell.no-games "-"]
        (RecordCell ties-allowed? result))]))

(defn- click-sort-results-link [mode js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :sort-results-by mode))

(rum/defc SortByToggle < rum/static
  [mode]
  [:div.sort-by-container
    [:label "Sort by:"]
    [:a {:class (str "left" (when (= mode sort-on-name) " active"))
         :href "#"
         :on-click (partial click-sort-results-link sort-on-name)
         :on-touch-start (partial click-sort-results-link sort-on-name)}
      "Team Name"]
    [:a {:class (str "right" (when (= mode sort-on-record) " active"))
         :href "#"
         :on-click (partial click-sort-results-link sort-on-record)
         :on-touch-start (partial click-sort-results-link sort-on-record)}
      "Record"]])

(rum/defc ResultsPage < rum/static
  [state]
  (let [ties-allowed? (:tiesAllowed state)
        results (games->results (:teams state) (:games state))
        sort-mode (:sort-results-by state)
        results (if (= sort-mode sort-on-name)
                  (sort-by :team-name results)
                  results)]
    [:article.results-container
      (SortByToggle sort-mode)
      [:table
        (ResultsTableHeader ties-allowed?)
        [:tbody
          (map-indexed #(ResultRow %1 %2 ties-allowed?) results)]]]))

;;------------------------------------------------------------------------------
;; Schedule Page
;;------------------------------------------------------------------------------

(defn- games->dates
  "Returns an ordered, distinct list of tournament dates."
  [games]
  (let [days-set (reduce (fn [dates-set game]
                           (conj dates-set (game->date game)))
                         #{}
                         games)]
    (sort days-set)))

(def date-format "YYYY-MM-DD HHmm")

(defn- format-time [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "h:mma")))

(defn- format-date [start-time]
  (let [js-moment (js/moment start-time date-format)]
    (.format js-moment "ddd, MMMM Do, YYYY")))

(rum/defc GameStatus < rum/static
  [status]
  [:span.status
    (condp = status
      scheduled-status
      "scheduled"

      in-progress-status
      "in progress"

      final-status
      "final"

      ;; NOTE: this should never happen
      "")])

;; TODO: highlight search match text in yellow; stretch goal :)
(rum/defc ScheduleRow < (merge rum/static index-key-fn-mixin)
  [idx game teams]
  (let [game-name (:name game)
        status (:status game)
        teamA-id (keyword (:teamA-id game))
        teamB-id (keyword (:teamB-id game))
        teamA-name (get-in teams [teamA-id :name])
        teamB-name (get-in teams [teamB-id :name])
        scoreA (:scoreA game)
        scoreB (:scoreB game)
        show-scores? (and (or (= status in-progress-status)
                              (= status final-status))
                          scoreA
                          scoreB)]
    ;; highlight this row if the game is in progress
    [:tr {:class (if (= status in-progress-status) "in-progress" "")}
      [:td.time-cell (format-time (:start-time game))]
      [:td.game-cell
        (if (and (not teamA-name) (not teamB-name))
          [:div.pending-game game-name]
          (list [:div {:key 1} teamA-name
                      (when show-scores?
                        (str " (" scoreA ")"))
                      [:span.vs "vs"]
                      (when show-scores?
                        (str "(" scoreB ") "))
                      teamB-name
                      (when status
                        (GameStatus status))]
                [:div.sub-name {:key 2} game-name]))]]))

(rum/defc SingleDaySchedule < (merge rum/static index-key-fn-mixin)
  [idx date teams games]
  (let [games-on-this-day (filter #(= date (game->date %)) games)
        games-on-this-day (sort-by :start-time games-on-this-day)]
    [:div
      [:h3 (format-date date)]
      [:table.schedule-tbl
        [:tbody
          (map-indexed #(ScheduleRow %1 %2 teams) games-on-this-day)]]]))

(defn- on-change-schedule-search [js-evt]
  (let [new-text (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc :schedule-search-text new-text)))

(defn- match-game? [teams search-txt game]
  (let [search-txt (lower-case search-txt)
        game-name (:name game)
        teamA-name (if-let [teamA-id (keyword (:teamA-id game))]
                      (get-in teams [teamA-id :name] "")
                      "")
        teamB-name (if-let [teamB-id (keyword (:teamB-id game))]
                      (get-in teams [teamB-id :name] "")
                      "")]
    (or (not= -1 (.indexOf (lower-case game-name) search-txt))
        (not= -1 (.indexOf (lower-case teamA-name) search-txt))
        (not= -1 (.indexOf (lower-case teamB-name) search-txt)))))

(defn- clear-search [js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :schedule-search-text ""))

(defn- toggle-hide-finished-games [js-evt]
  (.preventDefault js-evt)
  (swap! page-state update-in [:hide-finished-games?] not))

(rum/defc SchedulePage < rum/static
  [state]
  (let [games-vec (vals (:games state))
        teams (:teams state)
        search-txt (:schedule-search-text state)
        hide-finished-games? (:hide-finished-games? state)
        filtered-games1 (if-not (blank? search-txt)
                         (filter (partial match-game? teams search-txt) games-vec)
                         games-vec)
        filtered-games2 (if hide-finished-games?
                          (remove game-finished? filtered-games1)
                          filtered-games1)
        tourney-dates (games->dates filtered-games2)]
    [:article.schedule-container
      [:input {:class "big-input"
               :on-change on-change-schedule-search
               :placeholder "Search the schedule..."
               :type "text"
               :value search-txt}]
      [:div.option-container
        [:div.left
          (when-not (blank? search-txt)
            [:a.clear-search-link {:href "#"
                                   :on-click clear-search
                                   :on-touch-start clear-search}
              "clear search"])]
        [:div.right
          [:label.hide-finished-games
            {:on-click toggle-hide-finished-games
             :on-touch-start toggle-hide-finished-games}
            (if hide-finished-games?
              (SVGIcon "checked-17a35" "checkedBox")
              (SVGIcon "unchecked-09cdc" "uncheckedBox"))
            [:span.label-text "Hide finished games"]]]]
      (if (empty? filtered-games2)
        [:div.no-search-results "No games found."]
        (map-indexed #(SingleDaySchedule %1 %2 teams filtered-games2) tourney-dates))]))

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
  [current-tab tournament-has-started?]
  [:nav
    [:ul
      (Tab "Info" info-tab-id current-tab)
      (Tab "Schedule" schedule-tab-id current-tab)
      (Tab (if tournament-has-started? "Results" "Teams") results-tab-id current-tab)]])

;;------------------------------------------------------------------------------
;; Footer
;;------------------------------------------------------------------------------

(rum/defc Footer < rum/static
  []
  [:footer
    ;; TODO: add a "last updated" timestamp?
    [:div.left
      "powered by " [:a {:href tourney-bot-url} "TourneyBot"]]])

;;------------------------------------------------------------------------------
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc IndexApp < rum/static
  [state]
  (let [current-tab (:tab state)
        games (:games state)
        tournament-has-started? (not (or (not games)
                                         (zero? (count (vals games)))
                                         (every? game-scheduled? (vals games))))]
    [:div
      [:header
        [:h1 (:title state)]
        (NavTabs current-tab tournament-has-started?)]
      ;; NOTE: we fill this <div> with raw HTML content so it's important that
      ;;       react.js never touches it
      ;; TODO: change this to work as a Rum component
      [:article#infoContainer
        {:style {:display (if (= info-tab-id current-tab) "block" "none")}}]
      (when (= current-tab schedule-tab-id)
        (SchedulePage state))
      (when (= current-tab results-tab-id)
        (ResultsPage state))
      (Footer)]))

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "indexContainer"))

(defn- on-change-page-state
  "Render the page on every state change."
  [_kwd _the-atom _old-state new-state]
  (rum/mount (IndexApp new-state) app-container-el))

(add-watch page-state :main on-change-page-state)

;;------------------------------------------------------------------------------
;; Page Init
;;------------------------------------------------------------------------------

(def init-page!
  (once
    (fn []
      (fetch-tourney-state!)
      (fetch-info-page!)
      ;; trigger an initial render
      (swap! page-state identity))))

(init-page!)
