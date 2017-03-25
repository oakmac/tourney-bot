(ns tourneybot.data
  "Functions that operate on the tournament data structure."
  (:require
    [tourneybot.util :refer [js-log log half]]))

;;------------------------------------------------------------------------------
;; Constants
;;------------------------------------------------------------------------------

(def scheduled-status "scheduled")
(def in-progress-status "in_progress")
(def final-status "final")
(def game-statuses #{scheduled-status in-progress-status final-status})

;;------------------------------------------------------------------------------
;; Predicates
;;------------------------------------------------------------------------------

;; TODO: this can be replaced using a set of sets:
;; #{ #{teamA teamB}
;;    #{teamA teamB}
;;    ...}
(defn teams-already-played?
  "Returns false or a game where the two teams have previously played."
  [teamA-id teamB-id all-games]
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

(defn is-swiss-game? [g]
  (integer? (:swiss-round g)))

;; NOTE: "finished" is legacy here
(defn game-finished? [game]
  (or (= final-status (:status game))
      (= "finished" (:status game))))

(defn valid-score? [score]
  (and (integer? score)
       (>= score 0)))

(defn- has-pending-team? [game]
  (or (:pending-teamA game)
      (:pending-teamB game)))

;;------------------------------------------------------------------------------
;; Misc
;;------------------------------------------------------------------------------

(defn- winner
  "Returns the game-id of the winning team."
  [game]
  (if (> (:scoreA game) (:scoreB game))
    (:teamA-id game)
    (:teamB-id game)))

(defn- loser
  "Returns the game-id of the losing team."
  [game]
  (if (< (:scoreA game) (:scoreB game))
    (:teamA-id game)
    (:teamB-id game)))

;;------------------------------------------------------------------------------
;; Ensure tournament.json integrity
;;------------------------------------------------------------------------------

(defn- ensure-game-status
  "Game status must be valid."
  [game]
  (if-not (contains? game-statuses (:status game))
    (assoc game :status scheduled-status)
    game))

(defn- ensure-scores
  "Game scores must be integers."
  [game]
  (let [game2 (if-not (valid-score? (:scoreA game))
                (assoc game :scoreA 0)
                game)]
    (if-not (valid-score? (:scoreB game2))
      (assoc game2 :scoreB 0)
      game2)))

(defn- ensure-realistic-status
  "Game status cannot be 'scheduled' if there are scores."
  [game]
  (if (and (= scheduled-status (:status game))
           (not (zero? (+ (:scoreA game) (:scoreB game)))))
    (assoc game :status final-status)
    game))

(defn ensure-game [game]
  (-> game
      ensure-game-status
      ensure-scores
      ensure-realistic-status))

(defn- ensure-games
  "Games must have scores, status, and ids"
  [state]
  ;; TODO: write this
  state)

(defn- ensure-teams
  "Teams must have..."
  [state]
  ;; TODO: write this
  state)

(defn ensure-tournament-state [state]
  (-> state
      ensure-teams
      ensure-games))

;;------------------------------------------------------------------------------
;; Calculate Results
;;------------------------------------------------------------------------------

(def victory-points-for-a-win 3000)
(def victory-points-for-a-tie 1000)

(def empty-result
  {:games-lost 0
   :games-played 0
   :games-tied 0
   :games-won 0
   :points-diff 0
   :points-lost 0
   :points-played 0
   :points-won 0
   :team-id nil
   :team-name nil
   :team-captain nil
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
      :games-lost (if lost? (inc games-lost) games-lost)
      :games-played (inc games-played)
      :games-tied (if tied? (inc games-tied) games-tied)
      :games-won (if won? (inc games-won) games-won)
      :points-diff (+ points-diff scored-for (* -1 scored-against))
      :points-lost (+ points-lost scored-against)
      :points-played (+ points-played scoreA scoreB)
      :points-won (+ points-won scored-for)
      :victory-points (+ victory-points
                         (if won? victory-points-for-a-win 0)
                         (if tied? victory-points-for-a-tie 0)
                         scored-for
                         (* -1 scored-against)))))

(defn- team->results
  "Creates a result map for a single team."
  [teams games team-id]
  (let [team (get teams (keyword team-id))
        games-this-team-has-played (filter #(and (game-finished? %)
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

;;------------------------------------------------------------------------------
;; Determine Next Swiss Round Matchups
;;------------------------------------------------------------------------------

(defn create-matchups
  "Calculates the next Swiss round matchups.
   Returns a vector of matchups. Each matchup is a vector of [teamA-id teamB-id]
   The vectors are in-order. ie: the top team is at (ffirst) position"
  [teams games swiss-round]
  (let [;; find games below the target swiss round
        games-to-look-at (filter #(and (is-swiss-game? (second %))
                                       (< (:swiss-round (second %)) swiss-round)
                                       (game-finished? (second %)))
                                 games)
        ;; create a set of all the matchups that have occurred so far
        prior-matchups (reduce (fn [matchups game]
                                 (let [teamA-id (name (:teamA-id game ""))
                                       teamB-id (name (:teamB-id game ""))]
                                   (conj matchups #{teamA-id teamB-id})))
                               #{}
                               (vals games-to-look-at))
        ;; score the teams
        results (games->results teams games-to-look-at)
        ;; list to pull team-ids from
        sorted-team-ids (atom (vec (map :team-id results)))
        num-matchups-to-create (half (count @sorted-team-ids))
        ;; we will fill the new-matchups vector until the sorted-team-ids list is empty
        new-matchups (atom [])]
    ;; create the matchups for this swiss round
    (dotimes [i num-matchups-to-create]
      (let [;; take the first team in the list
            teamA-id (first @sorted-team-ids)
            ;; remove that team from the teams list
            _ (swap! sorted-team-ids subvec 1)
            ;; find the next closest team that has not already played teamA
            match-found? (atom false)
            j (atom 0)
            _ (while (and (not @match-found?)
                          (< @j (count @sorted-team-ids)))
                (let [team-id (nth @sorted-team-ids @j)
                      possible-matchup [teamA-id team-id]
                      possible-matchup-set (set possible-matchup)
                      teams-already-played? (contains? prior-matchups possible-matchup-set)]
                  (if teams-already-played?
                    (swap! j inc) ;; try the next team in the list
                    (do
                      ;; we found a match; exit this loop
                      (reset! match-found? true)
                      ;; add this matchup to the new-matchups vector
                      (swap! new-matchups conj possible-matchup)
                      ;; remove this team-id from the possible teams
                      ;; NOTE: this would faster using subvec
                      (swap! sorted-team-ids (fn [ids]
                                               (vec (remove #(= % team-id) ids))))))))]))
    @new-matchups))

;;------------------------------------------------------------------------------
;; Tournament Advancer
;;------------------------------------------------------------------------------













(def a-lot-of-points 50)

(def swiss-r2g3-id :game202)
(def swiss-r2g4-id :game203)

(defn- advance-swiss-r2
  [state]
  (let [new-state (atom state)
        swiss-r2g3 (get-in state [:games swiss-r2g3-id])
        swiss-r2g4 (get-in state [:games swiss-r2g4-id])]
    (when (and (game-finished? swiss-r2g3)
               (not (game-finished? swiss-r2g4)))
      (let [teams (:teams state)
            all-games (:games state)
            games1 (assoc all-games swiss-r2g4-id (merge swiss-r2g4
                                                         {:scoreA a-lot-of-points
                                                          :scoreB 0
                                                          :status final-status}))
            matchups1 (create-matchups teams games1 2)
            games2 (assoc all-games swiss-r2g4-id (merge swiss-r2g4
                                                         {:scoreA 0
                                                          :scoreB a-lot-of-points
                                                          :status final-status}))
            matchups2 (create-matchups teams games2 2)]
        (log matchups1)
        (log matchups2)))

    @new-state))










(defn- always-have-active-game
  [state]
  (let [new-state (atom state)
        games-list (map (fn [[game-id game]] (assoc game :game-id game-id)) (:games state))
        any-game-finished? (some game-finished? games-list)
        all-games-finished? (every? game-finished? games-list)
        sorted-games (sort-by :start-time games-list)
        unfinished-games (drop-while game-finished? sorted-games)
        active-game (first unfinished-games)]
    (when (and (:alwaysHaveActiveGame state)
               any-game-finished?
               (not all-games-finished?))
      (swap! new-state assoc-in [:games (:game-id active-game) :status] in-progress-status))
    @new-state))

(def semis-1v4-game-id :game601)
(def semis-2v3-game-id :game602)
(def fifth-place-game-id :game603)

(defn- advance-2017-indoor-tournament
  [state]
  (let [games-list (map (fn [[game-id game]] (assoc game :game-id game-id)) (:games state))
        all-swiss-games (filter is-swiss-game? games-list)
        all-swiss-games-finished? (every? game-finished? all-swiss-games)
        total-number-of-games-finished (count (filter game-finished? games-list))
        semis-1v4-game (get-in state [:games semis-1v4-game-id])
        semis-2v3-game (get-in state [:games semis-2v3-game-id])
        fifth-place-game (get-in state [:games fifth-place-game-id])
        new-state (atom state)]
    (when (and (= "2017 Houston Indoor") (:title state)
               all-swiss-games-finished?
               (= total-number-of-games-finished (count all-swiss-games))
               semis-1v4-game
               semis-2v3-game
               fifth-place-game)
      (let [results (games->results (:teams state) (:games state))]
        (when-not (:teamA-id semis-1v4-game)
          (swap! new-state update-in [:games semis-1v4-game-id] merge
            {:teamA-id (:team-id (nth results 0))
             :teamB-id (:team-id (nth results 3))}))
        (when-not (:teamA-id semis-2v3-game)
          (swap! new-state update-in [:games semis-2v3-game-id] merge
            {:teamA-id (:team-id (nth results 1))
             :teamB-id (:team-id (nth results 2))}))
        (when-not (:teamA-id fifth-place-game)
          (swap! new-state update-in [:games fifth-place-game-id] merge
            {:teamA-id (:team-id (nth results 4))
             :teamB-id (:team-id (nth results 5))}))))
    @new-state))

(defn- advance-pending-game
  [state pending-game]
  (let [pending-game-id (:game-id pending-game)

        pending-teamA (:pending-teamA pending-game)
        teamA-target-game-id (-> pending-teamA vals first keyword)
        teamA-target-game (get-in state [:games teamA-target-game-id])
        teamA-target-game-finished? (game-finished? teamA-target-game)
        teamA-target-game-winner (winner teamA-target-game)
        teamA-target-game-loser (loser teamA-target-game)

        pending-teamB (:pending-teamB pending-game)
        teamB-target-game-id (-> pending-teamB vals first keyword)
        teamB-target-game (get-in state [:games teamB-target-game-id])
        teamB-target-game-finished? (game-finished? teamB-target-game)
        teamB-target-game-winner (winner teamB-target-game)
        teamB-target-game-loser (loser teamB-target-game)

        new-state (atom state)]
    (when teamA-target-game-finished?
      (swap! new-state assoc-in [:games pending-game-id :teamA-id]
        (if (= :loser-of (-> pending-teamA keys first))
          teamA-target-game-loser
          teamA-target-game-winner)))
    (when teamB-target-game-finished?
      (swap! new-state assoc-in [:games pending-game-id :teamB-id]
        (if (= :loser-of (-> pending-teamB keys first))
          teamB-target-game-loser
          teamB-target-game-winner)))
    @new-state))

(defn- advance-pending-games
  "Given a tournament state, advances pending games if possible."
  [state]
  (let [all-games (:games state)
        games-list (map (fn [[game-id game]] (assoc game :game-id game-id)) all-games)
        games-with-pending-teams (filter has-pending-team? games-list)]
    (reduce
      advance-pending-game
      state
      games-with-pending-teams)))

(defn advance-tournament
  "Given a tournament state, tries to advance it.
   ie: calculates Swiss Round matchups, fills brackets, scores pools, etc"
  [state]
  (-> state
      advance-pending-games
      advance-2017-indoor-tournament
      always-have-active-game))
      ;; TODO: not finished yet
      ; advance-swiss-r2))
