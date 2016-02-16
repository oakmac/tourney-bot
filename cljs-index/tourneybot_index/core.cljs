(ns tourneybot-index.core
  (:require
    cljsjs.moment
    [tourneybot.util :refer [atom-logger by-id js-log log fetch-json-as-cljs]]
    [rum.core :as rum]))

;;------------------------------------------------------------------------------
;; Page State Atom
;;------------------------------------------------------------------------------

(def initial-page-state
  {:tab :results
   :sort-results-by :alpha})

(def page-state (atom initial-page-state))

;; NOTE: useful for debugging
; (add-watch page-state :log atom-logger)

;;------------------------------------------------------------------------------
;; Fetch Tournament State
;;------------------------------------------------------------------------------

;; TODO: allow the user to override this with a query param
(def fetch-interval-ms 5000)

(defn- fetch-tourney-state2 [new-state]
  (swap! page-state merge new-state))

(defn- fetch-tourney-state []
  (fetch-json-as-cljs "tournament.json" fetch-tourney-state2))

(js/setInterval fetch-tourney-state fetch-interval-ms)
(fetch-tourney-state)

;;------------------------------------------------------------------------------
;; Results Page
;;------------------------------------------------------------------------------

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
      :points-diff (+ points-diff scored-for (* -1 scored-against)))))

(defn- team->results
  "Creates a result map for a single team."
  [games team-id]
  (let [games-this-team-has-played (filter #(and (= (:status %) "finished")
                                                 (or (= (:teamA-id %) (name team-id))
                                                     (= (:teamB-id %) (name team-id))))
                                           (vals games))]
    (reduce add-game-to-result
            (assoc empty-result :team-id (name team-id))
            games-this-team-has-played)))

(defn- games->results
  "Creates a results list for all the teams."
  [teams games]
  (map (partial team->results games) (keys teams)))

(rum/defc ResultsTableHeader < rum/static
  [ties-allowed?]
  [:thead
    [:tr
      [:th {:row-span "2"} "Team"]
      [:th {:row-span "2"} "Wins"]
      [:th {:row-span "2"} "Losses"]
      (when ties-allowed?
        [:th {:row-span "2"} "Ties"])
      [:th {:row-span "2"} "Played"]
      [:th {:col-span "4"} "Points"]]
    [:tr
      [:th "Won"]
      [:th "Lost"]
      [:th "Played"]
      [:th "Diff"]]])

(rum/defc ResultRow < rum/static
  [ties-allowed? idx result]
  [:tr
    [:td (:team-id result)]
    [:td (:games-won result)]
    [:td (:games-lost result)]
    (when ties-allowed?
      [:td (:games-tied result)])
    [:td (:games-played result)]
    [:td (:points-won result)]
    [:td (:points-lost result)]
    [:td (:points-played result)]
    [:td (:points-diff result)]])

(rum/defc ResultsPage < rum/static
  [state]
  (let [results (games->results (:teams state) (:games state))
        ties-allowed? (:tiesAllowed state)]
    [:article.results
      [:h2 "Results"]
      [:table
        (ResultsTableHeader ties-allowed?)
        [:tbody
          (map-indexed (partial ResultRow ties-allowed?) results)]]]))

;;------------------------------------------------------------------------------
;; Schedule Page
;;------------------------------------------------------------------------------

(defn- game->date
  "Returns just the date string from a game."
  [game]
  (-> game
      :start-time
      (subs 0 10)))

(defn- get-tourney-dates
  "Returns an ordered, distinct list of tournament dates."
  [games]
  (let [days-set (reduce (fn [dates-set [game-id game]]
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
  (let [games-on-this-day (filter #(= date (game->date %)) (vals all-games))
        games-on-this-day (sort-by :start-time games-on-this-day)]
    [:div
      [:h3 (format-date date)]
      [:table
        [:tbody
          (map ScheduleRow games-on-this-day)]]]))

(rum/defc SchedulePage < rum/static
  [state]
  (let [tourney-dates (get-tourney-dates (:games state))]
    [:article.schedule
      [:h2 "Schedule"]
      (map (partial SingleDaySchedule (:games state)) tourney-dates)]))

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
  [:li (when (= tab-id current-tab) {:class "active"})
    [:a {:href "#"
         :on-click (partial click-tab tab-id)}
      name]])

(rum/defc NavTabs < rum/static
  [current-tab]
  [:nav
    [:ul
      (Tab "Info" :info current-tab)
      (Tab "Schedule" :schedule current-tab)
      (Tab "Results" :results current-tab)]])

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
      :info (InfoPage state)
      :schedule (SchedulePage state)
      :results (ResultsPage state)
      :else [:div "Error: invalid tab"])])

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

(defn- init!
  "Initialize the Index page."
  []
  (swap! page-state identity))

(init!)
