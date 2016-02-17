(ns tourneybot-index.core
  (:require
    cljsjs.moment
    [clojure.string :refer [blank? lower-case]]
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-json-as-cljs]]
    [rum.core :as rum]))

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

;; some constants
(def info-tab "INFO-TAB")
(def schedule-tab "SCHEDULE-TAB")
(def results-tab "RESULTS-TAB")
(def tab-values #{info-tab schedule-tab results-tab})

(def sort-on-name "SORT-BY-NAME")
(def sort-on-record "SORT-BY-RECORD")
(def sort-on-values #{sort-on-name sort-on-record})

(def initial-page-state
  {:tab info-tab
   :schedule-search-text ""
   :sort-results-by sort-on-name})

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

;; load any existing client state on startup
;; TODO: write some simple predicate functions to make sure the state is valid
(when-let [state-string (js/window.localStorage.getItem "client-state")]
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
    (js/window.localStorage.setItem "client-state" js-state-string)))

(add-watch page-state :client-state save-client-state)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

;; TODO: allow the user to override this with a query param
(def state-polling-ms 1000)

(defn- fetch-tourney-state2 [new-state]
  (swap! page-state merge new-state))

(defn- fetch-tourney-state []
  (fetch-json-as-cljs "tournament.json" fetch-tourney-state2))

(js/setInterval fetch-tourney-state state-polling-ms)
(fetch-tourney-state)

;;------------------------------------------------------------------------------
;; Calculate Results
;;------------------------------------------------------------------------------

;; TODO: probably move this result calculation code to cljs-shared

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
  [games team-id]
  (let [team (get-in @page-state [:teams (keyword team-id)])
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
  (let [results (map (partial team->results games) (keys teams))
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
    [:article.results
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

(defn- format-game [game]
  ;; TODO: make this smarter depending on how much game information we have
  (:name game))

(rum/defc ScheduleRow < rum/static
  [game]
  [:tr
    [:td.time (format-time (:start-time game))]
    [:td.game (format-game game)]])

(rum/defc SingleDaySchedule < rum/static
  [all-games date]
  (let [games-on-this-day (filter #(= date (game->date %)) all-games)
        games-on-this-day (sort-by :start-time games-on-this-day)]
    [:div
      [:h3 (format-date date)]
      [:table
        [:tbody
          (map ScheduleRow games-on-this-day)]]]))

(defn- on-change-schedule-search [js-evt]
  (let [new-text (aget js-evt "currentTarget" "value")]
    (swap! page-state assoc :schedule-search-text new-text)))

(defn- match-game? [search-txt game]
  (let [search-txt (lower-case search-txt)
        name (lower-case (:name game))]
    (or (not= -1 (.indexOf name search-txt)))))

(rum/defc SchedulePage < rum/static
  [state]
  (let [games (vals (:games state))
        search-txt (:schedule-search-text state)
        filtered-games (if-not (blank? search-txt)
                         (filter (partial match-game? search-txt) games)
                         games)
        tourney-dates (games->dates filtered-games)]
    [:article.schedule
      [:input {:class "big-input"
               :on-change on-change-schedule-search
               :placeholder "Search the schedule..."
               :type "text"
               :value search-txt}]
      (if (empty? filtered-games)
        [:div.no-search-results "No games found."]
        (map (partial SingleDaySchedule filtered-games) tourney-dates))]))

;;------------------------------------------------------------------------------
;; Info Page
;;------------------------------------------------------------------------------

(rum/defc InfoPage < rum/static
  [state]
  [:article "TODO: Info Page"])

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
;; Top Level Component
;;------------------------------------------------------------------------------

(rum/defc IndexApp < rum/static
  [state]
  [:div
    [:header
      [:h1 (:title state)]
      (NavTabs (:tab state))]
    (condp = (:tab state)
      info-tab
      (InfoPage state)

      schedule-tab
      (SchedulePage state)

      results-tab
      (ResultsPage state)

      ;; NOTE: this should never happen
      :else
      [:div "Error: invalid tab"])])

;;------------------------------------------------------------------------------
;; Render Loop
;;------------------------------------------------------------------------------

(def app-container-el (by-id "indexContainer"))

(defn- on-change-page-state [_kwd _the-atom _old-state new-state]
  ;; render the new state
  (rum/request-render
    (rum/mount (IndexApp new-state) app-container-el)))

(add-watch page-state :main on-change-page-state)

;;------------------------------------------------------------------------------
;; Init
;;------------------------------------------------------------------------------

;; TODO: update the <title> tag

(defn- init!
  "Initialize the Index page."
  []
  (swap! page-state identity))

(init!)
