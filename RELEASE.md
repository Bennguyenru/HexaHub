# Release guide

## Release branches/channels
* Alpha - git branch: dev
* Beta - git branch: beta
* Stable - git branch: master

## Alpha
Alpha channel is automatically released when [cr-editor-dev](http://ci.defold.com/builders/cr-editor-dev) is built on build bot.

## Beta
Important: *Make sure your branches are up to date!*

 1. If there is a pending Native Extension server change, [publish the stage server](https://github.com/defold/extender#releasing), which updates https://build-stage.defold.com

 1. Make sure dev builds are green

    Run the [Defold SDK Tests](https://jenkins-dfold.ess.midasplayer.com/job/defoldsdk-tests/) to test that the alpha release still works as expected

 1. Make sure dev is up to date:

        $ git checkout dev
        $ git pull

 1. Merge dev into beta;

        $ git checkout beta
        $ git pull
        $ git merge dev

    (The merge changed to a specific SHA1 commit of dev.)

 1. Push to beta

        $ git push

    This will trigger the beta channel to be built on build bot.

 1. Wait for [editor2-beta](http://ci.defold.com/builders/editor2-beta) to finish, make sure autobuilders are green.
 1. (Optional) Download and run beta:

    http://d.defold.com/editor2/`BETA-SHA1`/editor2/Defold-x86_64-darwin.dmg

    http://d.defold.com/editor2/`BETA-SHA1`/editor2/Defold-x86_64-win32.zip

    http://d.defold.com/editor2/`BETA-SHA1`/editor2/Defold-x86_64-linux.zip

    http://d.defold.com/archive/`BETA-SHA1`/beta/editor/Defold-macosx.cocoa.x86_64.dmg

    http://d.defold.com/archive/`BETA-SHA1`/beta/editor/Defold-win32.win32.x86.zip

    http://d.defold.com/archive/`BETA-SHA1`/beta/editor/Defold-linux.gtk.x86_64.zip

    The SHA1 can be found in the latest [cr-editor-beta](http://ci.defold.com/builders/cr-editor-beta) build, or `git log` on beta branch.

 1. (Optional) Verify new features and bug fixes.
 1. (Optional) Verify dev mobile apps.

 1. If everything is OK, time to release beta:

    $ `./scripts/build.py release --channel=beta --branch=beta`

    Important: *Make sure the SHA1 and channel is correct!*

 1. Build QRT test apps on [Jenkins](https://jenkins-defold.ess.midasplayer.com/job/defold-qrt_pipeline) ([old link](https://jenkins-stockholm.int.midasplayer.com/job/defold-qrt_pipeline/)) ([older link](https://jenkins-stockholm.int.midasplayer.com/job/defold-qrt/)).

    Log in and open "Build with Parameters"

    In "DEFOLD_CHANNEL" select "beta"

    Let ATL or PO know about the started build so they can monitor and smoke test the apps when build is done.

 1. Write release beta release notes (while CI builds the test apps)

 1. (Optional) Verify dev mobile apps from [app store](https://app-store.king.com/#/builds/DefoldQRT?filterByUserAgent=true&buildName=beta&onlyFeaturedBuilds=false)

 1. Verify release by updating an old editor, OSX, Win and Linux.

 1. Send notification email with release notes to defold-users@king.com

## Smoke Tests of Beta

### QRT
When the beta has been released the following apps needs to be bundled and sent to QRT:
* Defold Examples - iOS, Android, desktops
* Defold IAP Tester - iOS, Android (Google Play and Amazon)
* "Geometry Wars" - iOS, Android, desktops
* Defold Facebook test app - iOs, Android
* BBS - iOS, Android, desktops
* Presto - iOS, Android

Here is a [Jenkins](https://jenkins-stockholm.int.midasplayer.com/job/defold-qrt_pipeline/) ([old link](https://jenkins-stockholm.int.midasplayer.com/job/defold-qrt/)) to a build job that can do this for you. It uploads to [App Store/DefoldQRT](https://app-store.king.com/#/builds/DefoldQRT)

You can also download desktop and html5 versions from the artifacts on that page.

Upload the IAP Tester to Amazon by following [these instructions](https://kingfluence.com/display/Defold/How+to+upload+to+Amazon)

### Defold Team
The following smoke tests are currently performed by the team on each platform (OSX, Win, Linux):
* Start editor, build BBS for desktop and HTML5

## Stable

 1. Switch to master branch, merge from beta:

        $ git checkout master
        $ git pull
        $ git merge beta

 1. Push master!

        $ git push

    This will trigger a build of the engines and editors for stable.

 1. Wait for the editor build: [sign-editor-master](http://ci.defold.com/builders/sign-editor-master).

    Make a note of the release sha1. Either via the build page, or latest commit to the master branch on github

 1. Fetch editor via:

    http://d.defold.com/editor2/`STABLE-SHA1`/editor2/Defold-x86_64-darwin.dmg

    http://d.defold.com/editor2/`STABLE-SHA1`/editor2/Defold-x86_64-win32.zip

    http://d.defold.com/editor2/`STABLE-SHA1`/editor2/Defold-x86_64-linux.zip

    http://d.defold.com/archive/`STABLE-SHA1`/stable/editor/Defold-macosx.cocoa.x86_64.dmg

    http://d.defold.com/archive/`STABLE-SHA1`/stable/editor/Defold-win32.win32.x86.zip

    http://d.defold.com/archive/`STABLE-SHA1`/stable/editor/Defold-linux.gtk.x86_64.zip

 1. Tag the release in git:

        $ git tag -a X.Y.Z (same as version produced by the bump)
    Use tag message: Release X.Y.Z

        $ git push origin --tags
    This will push the tags to github, which is then used later in the release step.

### Publishing Stable Release

1. If everything is OK, time to release stable
1. If there is a pending Native Extension server change, [publish the production server](https://github.com/defold/extender#releasing), which updates https://build.defold.com
1. Next, release the stable engine/editor:

        $ ./scripts/build.py release
    Important: *Make sure the SHA1 and channel is correct!*

1. Verify release by updating an old editor, OSX, Win and Linux. (Currently a bit deprecated workflow, since noone uses editor-stable)
1. Publish latest API documentation by logging into http://www.defold.com/ref/update/latest
1. If there are Documentation changes pending, public those according to [the Documentation repo](https://github.com/defold/doc#build-and-publish)
1. Send notification email to defold-users@king.com and releasenotification@king.com
1. Post release notes on forum.defold.com
1. Post release notes on http://www.defold.com/admin/base/releasenote/

1. Merge master into dev

        $ git checkout dev
        $ git pull
        $ git merge master

1. Bump version:

        $ ./scripts/build.py bump
        $ git diff
        $ git add VERSION
        $ git commit
        > Message: "Bumped version to 1.2.xx"
        $ git push

1. Tell the editor team to update and release the Editor 2
