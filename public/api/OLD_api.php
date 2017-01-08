<?php
//------------------------------------------------------------------------------
// TourneyBot
// https://github.com/oakmac/tourney-bot
//
// Copyright (c) 2016, Chris Oakman
// Released under the ISC license
// https://github.com/oakmac/tourney-bot/blob/master/LICENSE.md
//------------------------------------------------------------------------------

// This file exposes a simple API for saving and retrieving tournament state
// from a local database.

// NOTE: update these values for your tournament and database
define('PASSWORD', 'SUPER_SECRET_PASSWORD_GOES_HERE');
define('DB_HOST', 'localhost');
define('DB_USER', 'DBUSER_GOES_HERE');
define('DB_PASS', 'DBPASS_GOES_HERE');
define('DB_NAME', 'DBNAME_GOES_HERE');

// connect to the database
$db_conn = mysqli_connect(DB_HOST, DB_USER, DB_PASS, DB_NAME);
if (mysqli_connect_errno()) {
    // echo "Failed to connect to MySQL: " . mysqli_connect_error();
    exit('Could not connect to the database :(');
}

// get the latest tournament state
if ($_GET['method'] === 'get-latest') {
    echo 'the latest tournament state!';
    die;
}

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

// update the games
if ($_POST['method'] === 'update-games' && $_POST['games-json'] !== '') {
    $games = json_decode($_POST['games-json'], true);

    // bail if the JSON is invalid
    if (! is_array($games)) {
        exit('false');
    }

    // grab the tournament state
    $tournamentStateJson = file_get_contents(TOURNAMENT_JSON_FILE);
    $tournamentState = json_decode($tournamentStateJson, true);

    // make sure the tournament state is valid
    if (! validTournamentState($tournamentState)) {
        exit('false');
    }

    // update the game
    $tournamentState['games'] = $games;

    // save tournament state
    file_put_contents(TOURNAMENT_JSON_FILE, json_encode($tournamentState));

    // return success
    exit('true');
}

// update the tournament state
if ($_POST['method'] === 'update-state' && $_POST['tournament-state'] !== '') {
    $tournamentState = json_decode($_POST['tournament-state'], true);

    // make sure the tournament state is valid
    if (! validTournamentState($tournamentState)) {
        exit('false');
    }

    // save tournament state
    file_put_contents(TOURNAMENT_JSON_FILE, json_encode($tournamentState));

    // return success
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

function getTournamentState($slug) {
    $query = '';

    $sql = 'SELECT data, version, ctime '.
           'FROM events '.
           'ORDER BY Lastname';
    $result = mysqli_query($db_conn, $sql);

    // Fetch all
    mysqli_fetch_all($result, MYSQLI_ASSOC);

    // Free result set
    mysqli_free_result($result);
    mysqli_close($conn);
}

?>
