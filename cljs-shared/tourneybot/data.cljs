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
