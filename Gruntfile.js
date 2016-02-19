const md5 = require('md5');

module.exports = function(grunt) {
'use strict';

//------------------------------------------------------------------------------
// Snowflake CSS
// TODO: this should become it's own module and published on npm
//------------------------------------------------------------------------------

function keys(o) {
  var a = [];
  for (var i in o) {
    if (o.hasOwnProperty(i) !== true) continue;
    a.push(i);
  }
  return a;
}

function arrToObj(arr) {
  var o = {};
  for (var i = 0; i < arr.length; i++) {
    o[ arr[i] ] = null;
  }
  return o;
}

function difference(arr1, arr2) {
  var o1 = arrToObj(arr1);
  var o2 = arrToObj(arr2);
  var delta = [];

  for (var i in o1) {
    if (o1.hasOwnProperty(i) !== true) continue;

    if (o2.hasOwnProperty(i) !== true) {
      delta.push(i)
    }
  }

  for (var i in o2) {
    if (o2.hasOwnProperty(i) !== true) continue;

    if (o1.hasOwnProperty(i) !== true) {
      delta.push(i)
    }
  }

  return delta.sort();
}

// Snowflake class names must contain at least one letter and one number
function hasNumbersAndLetters(str) {
  return str.search(/\d/) !== -1 &&
         str.search(/[a-z]/) !== -1;
}

// returns an array of unique Snowflake classes from a file
function extractSnowflakeClasses(filename, pattern) {
  if (! pattern) {
    pattern = /([a-z0-9]+-){1,}([abcdef0-9]){5}/g;
  }

  const fileContents = grunt.file.read(filename);
  const matches = fileContents.match(pattern);
  var classes = {};

  if (matches) {
    for (var i = 0; i < matches.length; i++) {
      var className = matches[i];
      var arr = className.split('-');
      var hash = arr[arr.length - 1];

      if (hasNumbersAndLetters(hash) === true) {
        classes[className] = null;
      }
    }
  }

  return keys(classes);
}

function snowflakeCount() {
  const cssClasses = extractSnowflakeClasses("public/css/main.min.css");
  const jsServer = extractSnowflakeClasses("app.js");
  const jsClient = extractSnowflakeClasses('public/js/cheatsheet.min.js');
  const docs = extractSnowflakeClasses('public/docs.json');
  const jsClasses = jsServer.concat(jsClient, docs);

  console.log(cssClasses.length + " class names found in css/main.min.css");
  console.log(jsClasses.length + " class names found in JS files");

  console.log("Classes found in one file but not the other:");
  console.log( difference(jsClasses, cssClasses) );
}

//------------------------------------------------------------------------------
// Cheatsheet Publish
//------------------------------------------------------------------------------

function preBuildSanityCheck() {
  if (! grunt.file.exists('public/js/admin.min.js')) {
    grunt.fail.warn('Could not find public/js/admin.min.js! Aborting...');
  }

  if (! grunt.file.exists('public/js/client.min.js')) {
    grunt.fail.warn('Could not find public/js/client.min.js! Aborting...');
  }

  grunt.log.writeln('Everything looks ok for a build.');
}

function hashAssets() {
  const cssFile = grunt.file.read('00-publish/css/main.min.css');
  const cssHash = md5(cssFile).substr(0, 8);

  const adminJsFile = grunt.file.read('00-publish/js/admin.min.js');
  const adminJsHash = md5(adminJsFile).substr(0, 8);

  const clientJsFile = grunt.file.read('00-publish/js/client.min.js');
  const clientJsHash = md5(clientJsFile).substr(0, 8);

  const adminHtmlFile = grunt.file.read('00-publish/admin/index.html');
  const clientHtmlFile = grunt.file.read('00-publish/index.html');

  // write the new files
  grunt.file.write('00-publish/css/main.min.' + cssHash + '.css', cssFile);
  grunt.file.write('00-publish/js/admin.min.' + adminJsHash + '.js', adminJsFile);
  grunt.file.write('00-publish/js/client.min.' + clientJsHash + '.js', clientJsFile);

  // delete the old files
  grunt.file.delete('00-publish/css/main.min.css');
  grunt.file.delete('00-publish/js/admin.min.js');
  grunt.file.delete('00-publish/js/client.min.js');

  // update the HTML files
  grunt.file.write('00-publish/admin/index.html',
    adminHtmlFile.replace('main.min.css', 'main.min.' + cssHash + '.css')
    .replace('admin.min.js', 'admin.min.' + adminJsHash + '.js'));

  grunt.file.write('00-publish/index.html',
    clientHtmlFile.replace('main.min.css', 'main.min.' + cssHash + '.css')
    .replace('client.min.js', 'client.min.' + clientJsHash + '.js'));

  // show some output
  grunt.log.writeln('00-publish/css/main.min.css → ' +
                    '00-publish/css/main.min.' + cssHash + '.css');
  grunt.log.writeln('00-publish/js/admin.min.js → ' +
                    '00-publish/js/admin.min.' + adminJsHash + '.js');
  grunt.log.writeln('00-publish/js/client.min.js → ' +
                    '00-publish/js/client.min.' + clientJsHash + '.js');
}

//------------------------------------------------------------------------------
// Grunt Config
//------------------------------------------------------------------------------

grunt.initConfig({

  clean: {
    options: {
      force: true
    },

    // remove all the files in the 00-publish folder
    pre: ['00-publish'],

    // remove some dev files
    post: [
      '00-publish/js/admin.js',
      '00-publish/js/client.js',
      '00-publish/index-dev.html',
      '00-publish/admin/index-dev.html'
    ]
  },

  copy: {
    publish: {
      files: [
        {expand: true, cwd: 'public/', src: ['**'], dest: '00-publish/'}
      ]
    }
  },

  less: {
    options: {
      compress: true
    },

    watch: {
      files: {
        'public/css/main.min.css': 'less/main.less'
      }
    }
  },

  watch: {
    options: {
      atBegin: true
    },

    less: {
      files: "less/*.less",
      tasks: "less:watch"
    }
  }

});

// load tasks from npm
grunt.loadNpmTasks('grunt-contrib-clean');
grunt.loadNpmTasks('grunt-contrib-copy');
grunt.loadNpmTasks('grunt-contrib-less');
grunt.loadNpmTasks('grunt-contrib-watch');

// custom tasks
grunt.registerTask('pre-build-sanity-check', preBuildSanityCheck);
grunt.registerTask('hash-assets', hashAssets);

grunt.registerTask('build', [
  'pre-build-sanity-check',
  'clean:pre',
  'less',
  'copy:publish',
  'hash-assets',
  'clean:post'
]);

grunt.registerTask('snowflake', snowflakeCount);
grunt.registerTask('default', 'watch');

// end module.exports
};
