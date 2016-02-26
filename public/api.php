<?php
//------------------------------------------------------------------------------
// TourneyBot
// https://github.com/oakmac/tourney-bot
//
// Copyright (c) 2016, Chris Oakman
// Released under the ISC license
// https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md
//------------------------------------------------------------------------------

// This file exposes a small, simple API for saving the tournament state to
// tournament.json

// IMPORTANT: update this password for your tournament!
define('PASSWORD', 'SUPER_SECRET_PASSWORD_GOES_HERE');
define('TOURNAMENT_JSON_FILE', 'tournament.json');

// check the password
if ($_POST['method'] === 'check-password') {
    if ($_POST['password'] === PASSWORD) {
        exit('true');
    }
    exit('false');
}

// NOTE: everything below this line requires a password
if ($_POST['password'] !== PASSWORD) {
    exit('wrong password');
}

// update a game
if ($_POST['method'] === 'update-game' && $_POST['game-id'] !== '' && $_POST['game-json'] !== '') {
    $gameId = $_POST['game-id'];
    $gameStr = $_POST['game-json'];
    $game = json_decode($gameStr, true);

    // bail if the JSON is invalid
    if (! is_array($game)) {
        exit('false');
    }

    // grab the tournament state
    $tournamentStateJson = file_get_contents(TOURNAMENT_JSON_FILE);
    $tournamentState = json_decode($tournamentStateJson, true);

    // make sure this game id exists
    if (! array_key_exists($gameId, $tournamentState['games']) ||
        ! is_array($tournamentState['games'][$gameId])) {
        exit('false');
    }

    // update the game
    $tournamentState['games'][$gameId] = $game;

    // save tournament state
    file_put_contents(TOURNAMENT_JSON_FILE, json_encode($tournamentState, JSON_PRETTY_PRINT));

    // return success
    exit('true');
}

// // save tournament state
// if ($_POST['method'] === 'save-state' && $_POST['tournament-state'] !== '') {
//     $stateStr = $_POST['tournament-state'];
//     $state = json_decode($stateStr, true);
//
//     // bail if the JSON is invalid
//     if (validTournamentState($state) !== true) {
//         exit('false');
//     }
//
//     // success
//     file_put_contents(TOURNAMENT_JSON_FILE, json_encode($state));
//     exit('true');
// }

// do nothing if they did not pass a valid method
exit('invalid method');

//------------------------------------------------------------------------------
// Functions
//------------------------------------------------------------------------------

// TODO: write a validGame function

// do some sanity-checking to makes sure the tournament state is valid
function validTournamentState($state) {
    return is_array($state) &&
           is_string($state['title']) &&
           is_array($state['teams']) &&
           is_array($state['games']);
}

?>
