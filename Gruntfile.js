const md5 = require('md5');

module.exports = function(grunt) {
'use strict';

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

// TODO: re-write this using a data structure
function hashAssets() {
  const adminCssFile = grunt.file.read('00-publish/css/admin.min.css');
  const adminCssHash = md5(adminCssFile).substr(0, 8);

  const clientCssFile = grunt.file.read('00-publish/css/client.min.css');
  const clientCssHash = md5(clientCssFile).substr(0, 8);

  const adminJsFile = grunt.file.read('00-publish/js/admin.min.js');
  const adminJsHash = md5(adminJsFile).substr(0, 8);

  const clientJsFile = grunt.file.read('00-publish/js/client.min.js');
  const clientJsHash = md5(clientJsFile).substr(0, 8);

  const adminHtmlFile = '00-publish/admin/index.html';
  const adminHtmlFileContents = grunt.file.read(adminHtmlFile);
  const clientHtmlFile = '00-publish/index.html';
  const clientHtmlFileContents = grunt.file.read(clientHtmlFile);

  // write the new files
  grunt.file.write('00-publish/css/admin.min.' + adminCssHash + '.css', adminCssFile);
  grunt.file.write('00-publish/css/client.min.' + clientCssHash + '.css', clientCssFile);
  grunt.file.write('00-publish/js/admin.min.' + adminJsHash + '.js', adminJsFile);
  grunt.file.write('00-publish/js/client.min.' + clientJsHash + '.js', clientJsFile);

  // delete the old files
  grunt.file.delete('00-publish/css/admin.min.css');
  grunt.file.delete('00-publish/css/client.min.css');
  grunt.file.delete('00-publish/js/admin.min.js');
  grunt.file.delete('00-publish/js/client.min.js');

  // update the HTML files
  grunt.file.write(adminHtmlFile,
    adminHtmlFileContents.replace('admin.min.css', 'admin.min.' + adminCssHash + '.css')
                         .replace('admin.min.js', 'admin.min.' + adminJsHash + '.js'));

  grunt.file.write(clientHtmlFile,
    clientHtmlFileContents.replace('client.min.css', 'client.min.' + clientCssHash + '.css')
                          .replace('client.min.js', 'client.min.' + clientJsHash + '.js'));

  // show some output
  grunt.log.writeln('00-publish/css/admin.min.css → ' +
                    '00-publish/css/admin.min.' + adminCssHash + '.css');
  grunt.log.writeln('00-publish/css/client.min.css → ' +
                    '00-publish/css/client.min.' + clientCssHash + '.css');
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
      '00-publish/js/admin-dev.js',
      '00-publish/js/client-dev.js',
      '00-publish/tournament.json'
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
        'public/css/admin.min.css': 'less/admin.less',
        'public/css/client.min.css': 'less/client.less'
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

grunt.registerTask('default', 'watch');

// end module.exports
};
