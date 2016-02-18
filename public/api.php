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

// save tournament state
if ($_POST['method'] === 'save-state' && $_POST['tournament-state'] !== '') {
    $stateStr = $_POST['tournament-state'];
    $state = json_decode($stateStr, true);

    // bail if the JSON is invalid
    if (validTournamentState($state) !== true) {
        exit('false');
    }

    // success
    file_put_contents(TOURNAMENT_JSON_FILE, json_encode($state));
    exit('true');
}

// do nothing if they did not pass a valid method
exit('invalid method');

//------------------------------------------------------------------------------
// Functions
//------------------------------------------------------------------------------

// do some sanity-checking to makes sure the tournament state is valid
function validTournamentState($state) {
    return is_array($state) &&
           is_string($state['title']) &&
           is_array($state['teams']) &&
           is_array($state['games']);
}

?>
