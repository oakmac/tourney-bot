(ns tourneybot-index.core
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
(def schedule-tab "SCHEDULE-TAB")
(def results-tab "RESULTS-TAB")
(def tab-values #{info-tab schedule-tab results-tab})

(def sort-on-name "SORT-BY-NAME")
(def sort-on-record "SORT-BY-RECORD")
(def sort-on-values #{sort-on-name sort-on-record})

(def tournament-state-url "tournament.json")
(def info-page-url "info.md")

;; TODO: allow this to be overriden with a query param
(def refresh-rate-ms 5000)

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:hide-finished-games? false
   :schedule-search-text ""
   :sort-results-by sort-on-name
   :tab info-tab})

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
;; Save UI-specific app state to localStorage
;;------------------------------------------------------------------------------

(def ls-key "index-page-state")

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

(def js-state-polling-interval nil)
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

;; begin the polling
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

;; fetch the info page markdown on load
(fetch-info-page!)

;; poll for updates every 5 minutes
(js/setInterval fetch-info-page! info-polling-ms)

;;------------------------------------------------------------------------------
;; Calculate Results
;;------------------------------------------------------------------------------

;; NOTE: we should probably move this result calculation code to cljs-shared

(def empty-result
  {:team-id nil
   :games-won 0
   :games-lost 0
   :games-tied 0
   :games-played 0
   :points-won 0
   :points-lost 0
   :points-played 0
   :points-diff 0
   :victory-points 0})

(defn- add-game-to-result [result {:keys [teamA-id teamB-id scoreA scoreB]}]
  (let [team-id (:team-id result)
        games-won (:games-won result)
        games-lost (:games-lost result)
        games-tied (:games-tied result)
        games-played (:games-played result)
        points-won (:points-won result)
        points-lost (:points-lost result)
        points-played (:points-played result)
        points-diff (:points-diff result)
        victory-points (:victory-points result)
        won? (or (and (= team-id teamA-id) (> scoreA scoreB))
                 (and (= team-id teamB-id) (> scoreB scoreA)))
        lost? (or (and (= team-id teamA-id) (< scoreA scoreB))
                  (and (= team-id teamB-id) (< scoreB scoreA)))
        tied? (= scoreA scoreB)
        scored-for (if (= team-id teamA-id) scoreA scoreB)
        scored-against (if (= team-id teamA-id) scoreB scoreA)]
    (assoc result
      :games-won (if won? (inc games-won) games-won)
      :games-lost (if lost? (inc games-lost) games-lost)
      :games-tied (if tied? (inc games-tied) games-tied)
      :games-played (inc games-played)
      :points-won (+ points-won scored-for)
      :points-lost (+ points-lost scored-against)
      :points-played (+ points-played scoreA scoreB)
      :points-diff (+ points-diff scored-for (* -1 scored-against))
      :victory-points (+ victory-points
                         (if won? 1 0)
                         (/ scored-for 1000)
                         (* -1 (/ scored-against 1000))))))

(defn- team->results
  "Creates a result map for a single team."
  [teams games team-id]
  (let [team (get teams (keyword team-id))
        games-this-team-has-played (filter #(and (= (:status %) "finished")
                                                 (or (= (:teamA-id %) (name team-id))
                                                     (= (:teamB-id %) (name team-id))))
                                           (vals games))]
    (reduce add-game-to-result
            (assoc empty-result :team-id (name team-id)
                                :team-name (:name team)
                                :team-captain (:captain team))
            games-this-team-has-played)))

(defn- compare-victory-points [a b]
  (let [a-games-played? (not (zero? (:games-played a)))
        b-games-played? (not (zero? (:games-played b)))
        a-victory-points (:victory-points a)
        b-victory-points (:victory-points b)]
    (cond
      (and a-games-played? (not b-games-played?))
      -1

      (and b-games-played? (not a-games-played?))
      1

      (> a-victory-points b-victory-points)
      -1

      (> b-victory-points a-victory-points)
      1

      :else
      0)))

(defn- games->results
  "Creates a results list for all the teams."
  [teams games]
  (let [results (map (partial team->results teams games) (keys teams))
        sorted-results (sort compare-victory-points results)]
    (map-indexed #(assoc %2 :place (inc %1)) sorted-results)))

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

(rum/defc ResultRow < rum/static
  [ties-allowed? result]
  [:tr
    [:td.place (str "#" (:place result))]
    [:td.team-name (TeamNameCell (:team-name result) (:team-captain result))]
    [:td.record-cell
      [:div.big-record
        [:span.big-num (:games-won result)]
        [:span.dash "-"]
        [:span.big-num (:games-lost result)]
        (when ties-allowed?
          (list [:span.dash "-"]
                [:span.big-num (:games-tied result)]))]
      [:div.small-points
        [:span.small-point (str "+" (:points-won result))]
        [:span ", "]
        [:span.small-point (str "-" (:points-lost result))]
        [:span ", "]
        [:span.small-point (if (neg? (:points-diff result))
                             (:points-diff result)
                             (str "+" (:points-diff result)))]]]])

(defn- click-sort-results-link [mode js-evt]
  (.preventDefault js-evt)
  (swap! page-state assoc :sort-results-by mode))

(rum/defc SortByToggle < rum/static
  [mode]
  [:div.sort-by-container
    [:label "Sort by:"]
    [:a {:class (if (= mode sort-on-name) "active" "")
         :href "#"
         :on-click (partial click-sort-results-link sort-on-name)
         :on-touch-start (partial click-sort-results-link sort-on-name)}
      "Team Name"]
    [:a {:class (if (= mode sort-on-record) "active" "")
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
          (map (partial ResultRow ties-allowed?) results)]]]))

;;------------------------------------------------------------------------------
;; Schedule Page
;;------------------------------------------------------------------------------

(defn- game->date
  "Returns just the date string from a game."
  [game]
  (-> game
      :start-time
      (subs 0 10)))

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
      "scheduled"
      "scheduled"

      "in_progress"
      "in progress"

      "finished"
      "finished"

      ;; NOTE: this should never happen
      "")])

;; TODO: highlight search match text in yellow; stretch goal :)
(rum/defc ScheduleRow < rum/static
  [teams game]
  (let [game-name (:name game)
        status (:status game)
        teamA-id (keyword (:teamA-id game))
        teamB-id (keyword (:teamB-id game))
        teamA-name (get-in teams [teamA-id :name])
        teamB-name (get-in teams [teamB-id :name])
        scoreA (:scoreA game)
        scoreB (:scoreB game)
        show-scores? (and (or (= status "in_progress")
                              (= status "finished"))
                          scoreA
                          scoreB)]
    ;; highlight this row if the game is in progress
    [:tr {:class (if (= status "in_progress") "in-progress" "")}
      [:td.time (format-time (:start-time game))]
      [:td.game
        (if (and (not teamA-name) (not teamB-name))
          game-name
          (list [:div teamA-name
                      (when show-scores?
                        (str " (" scoreA ")"))
                      [:span.vs "vs"]
                      (when show-scores?
                        (str "(" scoreB ") "))
                      teamB-name
                      (when status
                        (GameStatus status))]
                [:div.sub-name game-name]))]]))

(rum/defc SingleDaySchedule < rum/static
  [teams games date]
  (let [games-on-this-day (filter #(= date (game->date %)) games)
        games-on-this-day (sort-by :start-time games-on-this-day)]
    [:div
      [:h3 (format-date date)]
      [:table
        [:tbody
          (map (partial ScheduleRow teams) games-on-this-day)]]]))

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

;; TODO: add "hide finished games" checkbox

(rum/defc SchedulePage < rum/static
  [state]
  (let [games (vals (:games state))
        teams (:teams state)
        search-txt (:schedule-search-text state)
        filtered-games (if-not (blank? search-txt)
                         (filter (partial match-game? teams search-txt) games)
                         games)
        tourney-dates (games->dates filtered-games)]
    [:article.schedule-container
      [:input {:class "big-input"
               :on-change on-change-schedule-search
               :placeholder "Search the schedule..."
               :type "text"
               :value search-txt}]
      (if (empty? filtered-games)
        [:div.no-search-results "No games found."]
        (map (partial SingleDaySchedule teams filtered-games) tourney-dates))]))

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
      (Tab "Schedule" schedule-tab current-tab)
      (Tab "Results" results-tab current-tab)]])

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
  (let [current-tab (:tab state)]
    [:div
      [:header
        [:h1 (:title state)]
        (NavTabs current-tab)]
      ;; NOTE: we fill this <div> with raw HTML content so it's important that
      ;;       react.js never touches it
      [:article#infoContainer
        {:style {:display (if (= info-tab current-tab) "block" "none")}}]
      (when (= current-tab schedule-tab)
        (SchedulePage state))
      (when (= current-tab results-tab)
        (ResultsPage state))
      (Footer)]))

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "indexContainer"))

(defn- on-change-page-state
  "Render the page on every state change."
  [_kwd _the-atom _old-state new-state]
  (rum/request-render
    (rum/mount (IndexApp new-state) app-container-el)))

(add-watch page-state :main on-change-page-state)

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

(defn- init!
  "Initialize the Index page."
  []
  ;; trigger the initial render
  (swap! page-state identity))

(init!)
