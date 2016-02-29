# TourneyBot

This project is for the web application used to run the Houston Indoor Ultimate
tournament. An installation of this project can be seen at [houstonindoor.com]

The Houston Indoor tournament uses a [Swiss system] with some unique criteria.
In particular, since there is typically only one field available for an indoor
tournament, field time needs to be maximized with a game always playing. The
tournament is scheduled with exactly one minute of down time between games; just
enough time for teams to switch on and off the field.

This is a challenge for a Swiss system where you do not know the next round's
matchups until all the games in a round are played. This application provides
tools for dealing with this situation and ensuring that a "forward schedule" is
always available.

In addition, it provides a mobile-friendly website where players can see the
current schedule, their team results, and general tournament information. The
public-facing website receives near-realtime updates of all tournament
information, so players do not need to refresh the page in order to see the
latest information. See the [architecture] section for more information about
this feature.

## Screenshots

Here are some [screenshots](screenshots/) of TourneyBot.

## Development Setup

Install [Leiningen] and [Node.js]

```sh
# install node_modules (one-time)
npm install

# you may wish the run the following commands in separate console tabs / windows

# build CLJS files
lein clean && lein cljsbuild auto

# compile LESS into CSS
grunt watch

# run a local web server out of public/ on port 9955
node server.js 9955

# produce a build in the 00-publish/ folder
grunt build
```

## Installation

The steps for starting a new tournament are roughly as follows:

1. Make sure the CLJS-generated files exist by running `lein clean && lein cljsbuild once`
1. Create a build in the `00-publish` folder with the command `grunt build`
1. Copy the `00-publish/` folder to a public web server that has PHP installed.
1. Edit the `info.md` file as appropriate for your tournament.
1. Edit the `tournament.json` file as appropriate for your tournament.
1. Set a password in `api.php`

## Architecture

TODO: write this section

* client side `tournament.json` polling
* admin side state management
* the role of PHP
* info.md

## License

[ISC License]

[houstonindoor.com]:http://houstonindoor.com/2016
[architecture]:#architecture
[Swiss system]:https://en.wikipedia.org/wiki/Swiss-system_tournament
[Leiningen]:http://leiningen.org
[Node.js]:http://nodejs.org
[ISC License]:LICENSE.md
