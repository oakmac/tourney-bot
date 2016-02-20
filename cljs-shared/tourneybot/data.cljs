(ns tourneybot.data
  "Functions that operate on the tournament data structure.")

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def scheduled-status "scheduled")
(def in-progress-status "in_progress")
(def finished-status "finished")
(def game-statuses #{scheduled-status in-progress-status finished-status})

;;------------------------------------------------------------------------------
;; Ensure tournament.json integrity
;;------------------------------------------------------------------------------

;; TODO: write these functions

(defn- ensure-game-status
  "Game status must be valid."
  [state]
  state)

(defn- ensure-scores
  "Game scores must be integers."
  [state]
  state)

(defn- ensure-games
  "Games must have scores, status, and ids"
  [state]
  state)

(defn- ensure-teams
  "Teams must have..."
  [state]
  state)

(defn ensure-tournament-state [state]
  (-> state
      ensure-teams
      ensure-games))

;;------------------------------------------------------------------------------
;; Calculate Results
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

(defn- add-game-to-result [result game]
  (let [{:keys [team-id games-won games-lost games-tied games-played
                points-won points-lost points-played points-diff victory-points]}
        result

        {:keys [teamA-id teamB-id scoreA scoreB]}
        game

        ;; make sure all the team-ids are strings
        team-id (name team-id)
        teamA-id (name teamA-id)
        teamB-id (name teamB-id)

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
                         (if won? 2000 0)
                         (if tied? 1000 0)
                         scored-for
                         (* -1 scored-against)))))

(defn- team->results
  "Creates a result map for a single team."
  [teams games team-id]
  (let [team (get teams (keyword team-id))
        games-this-team-has-played (filter #(and (= (:status %) finished-status)
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

(defn games->results
  "Creates a results list for all the teams."
  [teams games]
  (let [results (map (partial team->results teams games) (keys teams))
        sorted-results (sort compare-victory-points results)]
    (map-indexed #(assoc %2 :place (inc %1)) sorted-results)))