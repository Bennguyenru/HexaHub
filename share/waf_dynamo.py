import os, sys, subprocess, shutil, re, stat, glob
import Build, Options, Utils, Task, Logs
from Configure import conf
from TaskGen import extension, taskgen, feature, after, before
from Logs import error
import cc, cxx
from Constants import RUN_ME

ANDROID_ROOT=os.path.join(os.environ['HOME'], 'android')
ANDROID_NDK_VERSION='8e'
ANDROID_NDK_API_VERSION='14'
ANDROID_API_VERSION='17'
ANDROID_GCC_VERSION='4.7'

# Workaround for a strange bug with the combination of ccache and clang
# Without CCACHE_CPP2 set breakpoint for source locations can't be set, e.g. b main.cpp:1234
os.environ['CCACHE_CPP2'] = 'yes'

def new_copy_task(name, input_ext, output_ext):
    def compile(task):
        with open(task.inputs[0].srcpath(task.env), 'rb') as in_f:
            with open(task.outputs[0].bldpath(task.env), 'wb') as out_f:
                out_f.write(in_f.read())

        return 0

    task = Task.task_type_from_func(name,
                                    func  = compile,
                                    color = 'PINK')

    @extension(input_ext)
    def copy_file(self, node):
        task = self.create_task(name)
        task.set_inputs(node)
        out = node.change_ext(output_ext)
        task.set_outputs(out)

IOS_TOOLCHAIN_ROOT='/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain'
ARM_DARWIN_ROOT='/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer'
IOS_SDK_VERSION="7.0"
# NOTE: Minimum iOS-version is also specified in Info.plist-files
# (MinimumOSVersion and perhaps DTPlatformVersion)
MIN_IOS_SDK_VERSION="5.0"

MIN_OSX_SDK_VERSION="10.7"

@feature('cc', 'cxx')
# We must apply this before the objc_hook below
# Otherwise will .mm-files not be compiled with -arch armv7 etc.
# I don't know if this is entirely correct
@before('apply_core')
def default_flags(self):

    platform = self.env['PLATFORM']
    build_platform = self.env['BUILD_PLATFORM']
    dynamo_home = self.env['DYNAMO_HOME']
    dynamo_ext = os.path.join(dynamo_home, "ext")

    if 'darwin' in platform:
        # OSX and iOS
        self.env.append_value('LINKFLAGS', ['-framework', 'Foundation'])

        if 'arm' in platform:
            # iOS
            self.env.append_value('LINKFLAGS', ['-framework', 'UIKit'])
        else:
            # OSX
            self.env.append_value('LINKFLAGS', ['-framework', 'AppKit'])

    if platform == "linux" or platform == "darwin" or platform == "x86_64-darwin":
        for f in ['CCFLAGS', 'CXXFLAGS']:
            self.env.append_value(f, ['-g', '-D__STDC_LIMIT_MACROS', '-DDDF_EXPOSE_DESCRIPTORS', '-Wall', '-fno-exceptions',])
            if platform == "darwin":
                self.env.append_value(f, ['-m32'])
            if platform == "darwin" or platform == "x86_64-darwin":
                # tr1/tuple isn't available on clang/darwin and gtest 1.5.0 assumes that
                # see corresponding flag in build_gtest.sh
                self.env.append_value(f, ['-DGTEST_USE_OWN_TR1_TUPLE=1'])
                # NOTE: Default libc++ changed from libstdc++ to libc++ on Maverick/iOS7.
                # Force libstdc++ for now
                self.env.append_value(f, ['-stdlib=libstdc++'])
                self.env.append_value(f, '-mmacosx-version-min=%s' % MIN_OSX_SDK_VERSION)
            # We link by default to uuid on linux. libuuid is wrapped in dlib (at least currently)
        if platform == "darwin":
            self.env.append_value('LINKFLAGS', ['-m32'])
        if platform == "darwin" or platform == "x86_64-darwin":
            # OSX only
            self.env.append_value('LINKFLAGS', ['-stdlib=libstdc++', '-mmacosx-version-min=%s' % MIN_OSX_SDK_VERSION, '-framework', 'Carbon'])
        elif platform == "linux":
            # Linux only
            pass
    elif platform == "armv7-darwin":
        #  NOTE: -lobjc was replaced with -fobjc-link-runtime in order to make facebook work with iOS 5 (dictionary subscription with [])
        for f in ['CCFLAGS', 'CXXFLAGS']:
            self.env.append_value(f, ['-DGTEST_USE_OWN_TR1_TUPLE=1'])
            # NOTE: Default libc++ changed from libstdc++ to libc++ on Maverick/iOS7.
            # Force libstdc++ for now
            self.env.append_value(f, ['-g', '-O2', '-stdlib=libstdc++', '-D__STDC_LIMIT_MACROS', '-DDDF_EXPOSE_DESCRIPTORS', '-Wall', '-fno-exceptions', '-arch', 'armv7', '-miphoneos-version-min=%s' % MIN_IOS_SDK_VERSION, '-isysroot', '%s/SDKs/iPhoneOS%s.sdk' % (ARM_DARWIN_ROOT, IOS_SDK_VERSION)])
        self.env.append_value('LINKFLAGS', [ '-arch', 'armv7', '-stdlib=libstdc++', '-fobjc-link-runtime', '-isysroot', '%s/SDKs/iPhoneOS%s.sdk' % (ARM_DARWIN_ROOT, IOS_SDK_VERSION), '-dead_strip', '-miphoneos-version-min=%s' % MIN_IOS_SDK_VERSION])
    elif platform == 'armv7-android':

        sysroot='%s/android-ndk-r%s/platforms/android-%s/arch-arm' % (ANDROID_ROOT, ANDROID_NDK_VERSION, ANDROID_NDK_API_VERSION)
        stl="%s/android-ndk-r%s/sources/cxx-stl/gnu-libstdc++/%s/include" % (ANDROID_ROOT, ANDROID_NDK_VERSION, ANDROID_GCC_VERSION)
        stl_lib="%s/android-ndk-r%s/sources/cxx-stl/gnu-libstdc++/%s/libs/armeabi-v7a" % (ANDROID_ROOT, ANDROID_NDK_VERSION, ANDROID_GCC_VERSION)
        stl_arch="%s/include" % stl_lib

        for f in ['CCFLAGS', 'CXXFLAGS']:
            # NOTE:
            # -mthumb and -funwind-tables removed from default flags
            # -fno-exceptions added
            self.env.append_value(f, ['-g', '-O2', '-gdwarf-2', '-D__STDC_LIMIT_MACROS', '-DDDF_EXPOSE_DESCRIPTORS', '-Wall',
                                      '-fpic', '-ffunction-sections', '-fstack-protector',
                                      '-D__ARM_ARCH_5__', '-D__ARM_ARCH_5T__', '-D__ARM_ARCH_5E__', '-D__ARM_ARCH_5TE__',
                                      '-Wno-psabi', '-march=armv7-a', '-mfloat-abi=softfp', '-mfpu=vfp',
                                      '-fomit-frame-pointer', '-fno-strict-aliasing', '-finline-limit=64', '-fno-exceptions',
                                      '-I%s/android-ndk-r%s/sources/android/native_app_glue' % (ANDROID_ROOT, ANDROID_NDK_VERSION),
                                      '-I%s/tmp/android-ndk-r%s/platforms/android-%s/arch-arm/usr/include' % (ANDROID_ROOT, ANDROID_NDK_VERSION, ANDROID_NDK_API_VERSION),
                                      '-I%s' % stl,
                                      '-I%s' % stl_arch,
                                      '--sysroot=%s' % sysroot,
                                      '-DANDROID', '-Wa,--noexecstack'])

        # TODO: Should be part of shared libraries
        # -Wl,-soname,libnative-activity.so -shared
        # -lgnustl_static -lsupc++
        self.env.append_value('LINKFLAGS', [
                '--sysroot=%s' % sysroot,
                '-Wl,--fix-cortex-a8', '-Wl,--no-undefined', '-Wl,-z,noexecstack', '-landroid',
                '-L%s' % stl_lib])
    else:
        for f in ['CCFLAGS', 'CXXFLAGS']:
            self.env.append_value(f, ['/Z7', '/MT', '/D__STDC_LIMIT_MACROS', '/DDDF_EXPOSE_DESCRIPTORS', '/D_CRT_SECURE_NO_WARNINGS', '/wd4996', '/wd4200'])
        self.env.append_value('LINKFLAGS', '/DEBUG')
        self.env.append_value('LINKFLAGS', ['shell32.lib', 'WS2_32.LIB'])

    if platform == build_platform:
        # Host libraries are installed to $PREFIX/lib
        libpath = os.path.join(dynamo_home, "lib")
    else:
        # Cross libraries are installed to $PREFIX/lib/PLATFORM
        libpath = os.path.join(dynamo_home, "lib", platform)

    # Create directory in order to avoid warning 'ld: warning: directory not found for option' before first install
    if not os.path.exists(libpath):
        os.mkdir(libpath)
    self.env.append_value('LIBPATH', libpath)

    self.env.append_value('CPPPATH', os.path.join(dynamo_ext, "include"))
    self.env.append_value('CPPPATH', os.path.join(dynamo_home, "include"))
    self.env.append_value('CPPPATH', os.path.join(dynamo_home, "include", platform))
    self.env.append_value('LIBPATH', os.path.join(dynamo_ext, "lib", platform))

@feature('cprogram', 'cxxprogram', 'cstaticlib', 'cshlib')
@after('apply_obj_vars')
def android_link_flags(self):
    platform = self.env['PLATFORM']
    build_platform = self.env['BUILD_PLATFORM']
    if re.match('arm.*?android', platform):
        self.link_task.env.append_value('LINKFLAGS', ['-lgnustl_static', '-lm', '-llog', '-lc'])
        self.link_task.env.append_value('LINKFLAGS', '-Wl,--no-undefined -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now'.split())

        if 'apk' in self.features:
            # NOTE: This is a hack We change cprogram -> cshlib
            # but it's probably to late. It works for the name though (libX.so and not X)
            self.link_task.env.append_value('LINKFLAGS', ['-shared'])

@feature('apk')
@before('apply_core')
def apply_apk_test(self):
    platform = self.env['PLATFORM']
    build_platform = self.env['BUILD_PLATFORM']
    if re.match('arm.*?android', platform):
        self.features.remove('cprogram')
        self.features.append('cshlib')

# Install all static libraries by default
@feature('cstaticlib')
@after('default_cc')
@before('apply_core')
def default_install_staticlib(self):
    self.default_install_path = self.env.LIBDIR

@feature('cshlib')
@after('default_cc')
@before('apply_core')
def default_install_shlib(self):
    # Force installation dir to LIBDIR.
    # Default on windows is BINDIR
    self.default_install_path = self.env.LIBDIR

# objective-c++ support
if sys.platform == "darwin":
    EXT_OBJCXX = ['.mm']
    @extension(EXT_OBJCXX)
    def objc_hook(self, node):
        tsk = cxx.cxx_hook(self, node)
        tsk.env.append_unique('CXXFLAGS', tsk.env['GCC-OBJCXX'])
        tsk.env.append_unique('LINKFLAGS', tsk.env['GCC-OBJCLINK'])

# objective-c support
if sys.platform == "darwin":
    EXT_OBJC = ['.m']
    @extension(EXT_OBJC)
    def objc_hook(self, node):
        tsk = cc.c_hook(self, node)
        tsk.env.append_unique('CXXFLAGS', tsk.env['GCC-OBJCC'])
        tsk.env.append_unique('LINKFLAGS', tsk.env['GCC-OBJCLINK'])

# iPhone bundle and signing support
RESOURCE_RULES_PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
        <key>rules</key>
        <dict>
                <key>.*</key>
                <true/>
                <key>Info.plist</key>
                <dict>
                        <key>omit</key>
                        <true/>
                        <key>weight</key>
                        <real>10</real>
                </dict>
                <key>ResourceRules.plist</key>
                <dict>
                        <key>omit</key>
                        <true/>
                        <key>weight</key>
                        <real>100</real>
                </dict>
        </dict>
</dict>
</plist>
"""

# The following is removed info.plist
# <key>NSMainNibFile</key>
# <string>MainWindow</string>
# We manage our own setup. At least for now

# NOTE: fb355198514515820 is HelloFBSample appid and for testing only
# This INFO_PLIST is only used for development
INFO_PLIST = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
        <key>BuildMachineOSBuild</key>
        <string>10K540</string>
        <key>CFBundleDevelopmentRegion</key>
        <string>en</string>
        <key>CFBundleDisplayName</key>
        <string>%(executable)s</string>
        <key>CFBundleExecutable</key>
        <string>%(executable)s</string>
        <key>CFBundleIdentifier</key>
        <string>%(bundleid)s</string>
        <key>CFBundleInfoDictionaryVersion</key>
        <string>6.0</string>
        <key>CFBundleName</key>
        <string>%(executable)s</string>
        <key>CFBundlePackageType</key>
        <string>APPL</string>
        <key>CFBundleResourceSpecification</key>
        <string>ResourceRules.plist</string>
        <key>CFBundleShortVersionString</key>
        <string>1.0</string>
        <key>CFBundleSignature</key>
        <string>????</string>
        <key>CFBundleSupportedPlatforms</key>
        <array>
                <string>iPhoneOS</string>
        </array>
        <key>CFBundleVersion</key>
        <string>1.0</string>
        <key>DTCompiler</key>
        <string>com.apple.compilers.llvmgcc42</string>
        <key>DTPlatformBuild</key>
        <string>8H7</string>
        <key>DTPlatformName</key>
        <string>iphoneos</string>
        <key>DTPlatformVersion</key>
        <string>4.3</string>
        <key>DTSDKBuild</key>
        <string>8H7</string>
        <key>DTSDKName</key>
        <string>iphoneos4.3</string>
        <key>DTXcode</key>
        <string>0402</string>
        <key>DTXcodeBuild</key>
        <string>4A2002a</string>
        <key>UIFileSharingEnabled</key>
        <true/>
        <key>LSRequiresIPhoneOS</key>
        <true/>
        <key>MinimumOSVersion</key>
        <string>5.0</string>
        <key>UIDeviceFamily</key>
        <array>
                <integer>1</integer>
                <integer>2</integer>
        </array>
        <key>UIStatusBarHidden</key>
        <true/>
        <key>UIViewControllerBasedStatusBarAppearance</key>
        <false/>
        <key>UISupportedInterfaceOrientations</key>
        <array>
                <string>UIInterfaceOrientationPortrait</string>
                <string>UIInterfaceOrientationPortraitUpsideDown</string>
                <string>UIInterfaceOrientationLandscapeLeft</string>
                <string>UIInterfaceOrientationLandscapeRight</string>
        </array>
        <key>UISupportedInterfaceOrientations~ipad</key>
        <array>
                <string>UIInterfaceOrientationPortrait</string>
                <string>UIInterfaceOrientationPortraitUpsideDown</string>
                <string>UIInterfaceOrientationLandscapeLeft</string>
                <string>UIInterfaceOrientationLandscapeRight</string>
        </array>

        <key>CFBundleURLTypes</key>
        <array>
                <dict>
                        <key>CFBundleURLSchemes</key>
                        <array>
                                <string>fb355198514515820</string>
                        </array>
                </dict>
        </array>
</dict>
</plist>
"""
def codesign(task):
    bld = task.generator.bld

    exe_path = task.exe.bldpath(task.env)
    signed_exe_path = task.signed_exe.bldpath(task.env)
    shutil.copy(exe_path, signed_exe_path)

    signed_exe_dir = os.path.dirname(signed_exe_path)

    identity = task.env.IDENTITY
    if not identity:
        identity = 'iPhone Developer'

    mobileprovision = task.provision
    if not mobileprovision:
        mobileprovision = 'engine_profile.mobileprovision'
    mobileprovision_path = os.path.join(task.env['DYNAMO_HOME'], 'share', mobileprovision)
    shutil.copyfile(mobileprovision_path, os.path.join(signed_exe_dir, 'embedded.mobileprovision'))

    entitlements = task.entitlements
    if not entitlements:
        entitlements = 'engine_profile.xcent'
    entitlements_path = os.path.join(task.env['DYNAMO_HOME'], 'share', entitlements)
    resource_rules_plist_file = task.resource_rules_plist.bldpath(task.env)

    ret = bld.exec_command('CODESIGN_ALLOCATE=/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/usr/bin/codesign_allocate codesign -f -s "%s" --resource-rules=%s --entitlements %s %s' % (identity, resource_rules_plist_file, entitlements_path, signed_exe_dir))
    if ret != 0:
        error('Error running codesign')
        return 1

    return 0

Task.task_type_from_func('codesign',
                         func = codesign,
                         #color = 'RED',
                         after  = 'app_bundle')

def app_bundle(task):
    info_plist_file = open(task.info_plist.bldpath(task.env), 'wb')
    bundleid = 'com.defold.%s' % task.exe_name
    if task.bundleid:
        bundleid = task.bundleid
    info_plist_file.write(INFO_PLIST % { 'executable' : task.exe_name, 'bundleid' : bundleid })
    info_plist_file.close()

    resource_rules_plist_file = open(task.resource_rules_plist.bldpath(task.env), 'wb')
    resource_rules_plist_file.write(RESOURCE_RULES_PLIST)
    resource_rules_plist_file.close()

    return 0

def create_export_symbols(task):
    with open(task.outputs[0].bldpath(task.env), 'wb') as out_f:
        for name in Utils.to_list(task.exported_symbols):
            print >>out_f, 'extern "C" void %s();' % name
        print >>out_f, "void dmExportedSymbols() {"
        for name in Utils.to_list(task.exported_symbols):
            print >>out_f, "    %s();" % name
        print >>out_f, "}"

    return 0

task = Task.task_type_from_func('create_export_symbols',
                                func  = create_export_symbols,
                                color = 'PINK',
                                before  = 'cc cxx')

task.runnable_status = lambda self: RUN_ME

@taskgen
@feature('cprogram')
@before('apply_core')
def export_symbols(self):
    # Force inclusion of symbol, e.g. from static libraries
    # We used to use -Wl,-exported_symbol on Darwin but changed
    # to a more general solution by generating a .cpp-file with
    # a function which references the symbols in question
    Utils.def_attrs(self, exported_symbols=[])
    if not self.exported_symbols:
        return

    exported_symbols = self.path.find_or_declare('__exported_symbols_%d.cpp' % self.idx)
    self.allnodes.append(exported_symbols)

    task = self.create_task('create_export_symbols')
    task.exported_symbols = self.exported_symbols
    task.set_outputs([exported_symbols])

Task.task_type_from_func('app_bundle',
                         func = app_bundle,
                         vars = ['SRC', 'DST'],
                         #color = 'RED',
                         after  = 'cxx_link cc_link static_link')

@taskgen
@after('apply_link')
@feature('cprogram')
def create_app_bundle(self):
    if not re.match('arm.*?darwin', self.env['PLATFORM']):
        return
    Utils.def_attrs(self, bundleid = None, provision = None, entitlements = None)

    app_bundle_task = self.create_task('app_bundle', self.env)
    app_bundle_task.bundleid = self.bundleid
    app_bundle_task.set_inputs(self.link_task.outputs)

    exe_name = self.link_task.outputs[0].name
    app_bundle_task.exe_name = exe_name

    info_plist = self.path.exclusive_build_node("%s.app/Info.plist" % exe_name)
    app_bundle_task.info_plist = info_plist

    resource_rules_plist = self.path.exclusive_build_node("%s.app/ResourceRules.plist" % exe_name)
    app_bundle_task.resource_rules_plist = resource_rules_plist

    app_bundle_task.set_outputs([info_plist, resource_rules_plist])

    self.app_bundle_task = app_bundle_task

    if not Options.options.skip_codesign:
        signed_exe = self.path.exclusive_build_node("%s.app/%s" % (exe_name, exe_name))

        codesign = self.create_task('codesign', self.env)
        codesign.provision = self.provision
        codesign.entitlements = self.entitlements
        codesign.resource_rules_plist = resource_rules_plist
        codesign.set_inputs(self.link_task.outputs)
        codesign.set_outputs(signed_exe)

        codesign.exe = self.link_task.outputs[0]
        codesign.signed_exe = signed_exe

# TODO: We should support a custom AndroidManifest.xml-file in waf
# Or at least a decent templating system, e.g. mustache
ANDROID_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<!-- BEGIN_INCLUDE(manifest) -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="%(package)s"
        android:versionCode="1"
        android:versionName="1.0">

    <uses-feature android:required="true" android:glEsVersion="0x00020000" />
    <uses-sdk android:minSdkVersion="9" />
    <application android:label="%(app_name)s" android:hasCode="true" android:debuggable="true">

        <!-- For GCM (push) -->
        <meta-data
            android:name="com.google.android.gms.version"
            android:value="@integer/google_play_services_version" />

        <activity android:name="com.dynamo.android.DefoldActivity"
                android:label="%(app_name)s"
                android:configChanges="orientation|keyboardHidden"
                android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
            <meta-data android:name="android.app.lib_name"
                    android:value="%(lib_name)s" />
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name="com.dynamo.android.DispatcherActivity" android:theme="@android:style/Theme.NoDisplay">
        </activity>

        <!-- For GCM (push) -->
        <activity android:name="com.defold.push.PushDispatchActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="com.defold.push.FORWARD" />
                <category android:name="com.defold.push" />
            </intent-filter>
        </activity>
        <receiver
            android:name="com.defold.push.GcmBroadcastReceiver"
            android:permission="com.google.android.c2dm.permission.SEND" >
            <intent-filter>
                <action android:name="com.google.android.c2dm.intent.RECEIVE" />
                <action android:name="com.defold.push.FORWARD" />
                <category android:name="com.defold.push" />
            </intent-filter>
        </receiver>

        %(extra_activities)s
    </application>
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />

    <!-- For GCM (push) -->
    <!-- NOTE: Package name from actual app here! -->
    <permission android:name="%(package)s.permission.C2D_MESSAGE" android:protectionLevel="signature" />
    <uses-permission android:name="android.permission.GET_ACCOUNTS" />
    <!-- NOTE: Package name from actual app here! -->
    <uses-permission android:name="%(package)s.permission.C2D_MESSAGE" />
    <uses-permission android:name="com.google.android.c2dm.permission.RECEIVE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />


</manifest>
<!-- END_INCLUDE(manifest) -->
"""

ANDROID_STUB = """
struct android_app;

extern void _glfwPreMain(struct android_app* state);
extern void app_dummy();

void android_main(struct android_app* state)
{
    // Make sure glue isn't stripped.
    app_dummy();
    _glfwPreMain(state);
}
"""

def android_package(task):
    # TODO: This is a complete mess and should be split in several smaller tasks!

    bld = task.generator.bld

    activities = ''
    for activity, attr in task.activities:
        activities += '<activity android:name="%s" %s/>' % (activity, attr)

    package = 'com.defold.%s' % task.exe_name
    if task.android_package:
        package = task.android_package

    manifest_file = open(task.manifest.bldpath(task.env), 'wb')
    manifest_file.write(ANDROID_MANIFEST % { 'package' : package, 'app_name' : task.exe_name, 'lib_name' : task.exe_name, 'extra_activities' : activities })
    manifest_file.close()

    aapt = '%s/android-sdk/platform-tools/aapt' % (ANDROID_ROOT)
    dx = '%s/android-sdk/platform-tools/dx' % (ANDROID_ROOT)
    dynamo_home = task.env['DYNAMO_HOME']
    android_jar = '%s/ext/share/java/android.jar' % (dynamo_home)
    res_dirs = glob.glob('%s/ext/share/java/res/*' % (dynamo_home))
    manifest = task.manifest.abspath(task.env)
    dme_and = os.path.normpath(os.path.join(os.path.dirname(task.manifest.abspath(task.env)), '..', '..'))
    r_java_gen_dir = task.r_java_gen_dir.abspath(task.env)
    r_jar = task.r_jar.abspath(task.env)

    libs = os.path.join(dme_and, 'libs')
    bin = os.path.join(dme_and, 'bin')
    bin_cls = os.path.join(bin, 'classes')
    dx_libs = os.path.join(bin, 'dexedLibs')
    gen = os.path.join(dme_and, 'gen')
    ap_ = task.ap_.abspath(task.env)
    native_lib = task.native_lib.abspath(task.env)
    gdbserver = task.gdbserver.abspath(task.env)

    bld.exec_command('mkdir -p %s' % (libs))
    bld.exec_command('mkdir -p %s' % (bin))
    bld.exec_command('mkdir -p %s' % (bin_cls))
    bld.exec_command('mkdir -p %s' % (dx_libs))
    bld.exec_command('mkdir -p %s' % (gen))
    bld.exec_command('mkdir -p %s' % (r_java_gen_dir))
    shutil.copy(task.native_lib_in.abspath(task.env), native_lib)
    shutil.copy('%s/android-ndk-r%s/prebuilt/android-arm/gdbserver/gdbserver' % (ANDROID_ROOT, ANDROID_NDK_VERSION), gdbserver)

    res_args = ""
    for d in res_dirs:
        res_args += ' -S %s' % d

    ret = bld.exec_command('%s package -f -m --output-text-symbols %s --auto-add-overlay -M %s -I %s -J %s --generate-dependencies -G %s %s' % (aapt, bin, manifest, android_jar, gen, os.path.join(bin, 'proguard.txt'), res_args))
    if ret != 0:
        error('Error running aapt')
        return 1

    clspath = ':'.join(task.jars)
    dx_jars = []
    for jar in task.jars:
        dx_jar = os.path.join(dx_libs, os.path.basename(jar))
        dx_jars.append(jar)
    dx_jars.append(r_jar)

    if task.extra_packages:
        extra_packages_cmd = '--extra-packages %s' % task.extra_packages[0]
    else:
        extra_packages_cmd = ''

    ret = bld.exec_command('%s package --no-crunch -f --debug-mode --auto-add-overlay -M %s -I %s %s -F %s -m -J %s %s' % (aapt, manifest, android_jar, res_args, ap_, r_java_gen_dir, extra_packages_cmd))
    if ret != 0:
        error('Error running aapt')
        return 1

    r_java_files = []
    for root, dirs, files in os.walk(r_java_gen_dir):
        for f in files:
            p = os.path.join(root, f)
            r_java_files.append(p)

    ret = bld.exec_command('%s %s' % (task.env['JAVAC'][0], ' '.join(r_java_files)))
    if ret != 0:
        error('Error compiling R.java files')
        return 1

    for p in r_java_files:
        os.unlink(p)

    ret = bld.exec_command('%s cf %s -C %s .' % (task.env['JAR'][0], r_jar, r_java_gen_dir))
    if ret != 0:
        error('Error creating jar of compiled R.java files')
        return 1

    if dx_jars:
        ret = bld.exec_command('%s --dex --output %s %s' % (dx, task.classes_dex.abspath(task.env), ' '.join(dx_jars)))
        if ret != 0:
            error('Error running dx')
            return 1

        # We can't use with statement here due to http://bugs.python.org/issue5511
        from zipfile import ZipFile
        f = ZipFile(ap_, 'a')
        f.write(task.classes_dex.abspath(task.env), 'classes.dex')
        f.close()

    apkbuilder = '%s/android-sdk/tools/apkbuilder' % (ANDROID_ROOT)
    apk_unaligned = task.apk_unaligned.abspath(task.env)
    libs_dir = task.native_lib.parent.parent.abspath(task.env)

    ret = bld.exec_command('%s %s -v -z %s -nf %s -d' % (apkbuilder, apk_unaligned, ap_, libs_dir))

    if ret != 0:
        error('Error running apkbuilder')
        return 1

    apk = task.apk.abspath(task.env)
    zipalign = '%s/android-sdk/tools/zipalign' % (ANDROID_ROOT)
    ret = bld.exec_command('%s -f 4 %s %s' % (zipalign, apk_unaligned, apk))
    if ret != 0:
        error('Error running zipalign')
        return 1

    with open(task.android_mk.abspath(task.env), 'wb') as f:
        print >>f, 'APP_ABI := armeabi-v7a'

    with open(task.application_mk.abspath(task.env), 'wb') as f:
        print >>f, ''

    with open(task.gdb_setup.abspath(task.env), 'wb') as f:
        print >>f, 'set solib-search-path ./libs/armeabi-v7a:./obj/local/armeabi-v7a/'

    return 0

Task.task_type_from_func('android_package',
                         func = android_package,
                         vars = ['SRC', 'DST'],
                         after  = 'cxx_link cc_link static_link')

@taskgen
@after('apply_link')
@feature('apk')
def create_android_package(self):
    if not re.match('arm.*?android', self.env['PLATFORM']):
        return
    Utils.def_attrs(self, android_package = None)

    android_package_task = self.create_task('android_package', self.env)
    android_package_task.set_inputs(self.link_task.outputs)
    android_package_task.android_package = self.android_package

    Utils.def_attrs(self, activities=[], extra_packages = "")
    android_package_task.activities = Utils.to_list(self.activities)
    android_package_task.extra_packages = Utils.to_list(self.extra_packages)

    Utils.def_attrs(self, jars=[])
    android_package_task.jars = Utils.to_list(self.jars)

    exe_name = self.name
    lib_name = self.link_task.outputs[0].name

    android_package_task.exe_name = exe_name

    manifest = self.path.exclusive_build_node("%s.android/AndroidManifest.xml" % exe_name)
    android_package_task.manifest = manifest

    native_lib = self.path.exclusive_build_node("%s.android/libs/armeabi-v7a/%s" % (exe_name, lib_name))
    android_package_task.native_lib = native_lib
    android_package_task.native_lib_in = self.link_task.outputs[0]

    gdbserver = self.path.exclusive_build_node("%s.android/libs/armeabi-v7a/gdbserver" % (exe_name))
    android_package_task.gdbserver = gdbserver

    ap_ = self.path.exclusive_build_node("%s.android/%s.ap_" % (exe_name, exe_name))
    android_package_task.ap_ = ap_

    apk_unaligned = self.path.exclusive_build_node("%s.android/%s-unaligned.apk" % (exe_name, exe_name))
    android_package_task.apk_unaligned = apk_unaligned

    apk = self.path.exclusive_build_node("%s.android/%s.apk" % (exe_name, exe_name))
    android_package_task.apk = apk

    android_package_task.classes_dex = self.path.exclusive_build_node("%s.android/classes.dex" % (exe_name))

    android_package_task.r_java_gen_dir = self.path.exclusive_build_node("%s.android/gen" % (exe_name))
    android_package_task.r_jar = self.path.exclusive_build_node("%s.android/r.jar" % (exe_name))

    # NOTE: These files are required for ndk-gdb
    android_package_task.android_mk = self.path.exclusive_build_node("%s.android/jni/Android.mk" % (exe_name))
    android_package_task.application_mk = self.path.exclusive_build_node("%s.android/jni/Application.mk" % (exe_name))
    android_package_task.gdb_setup = self.path.exclusive_build_node("%s.android/libs/armeabi-v7a/gdb.setup" % (exe_name))

    android_package_task.set_outputs([native_lib, manifest, ap_, apk_unaligned, apk, gdbserver,
                                      android_package_task.android_mk, android_package_task.application_mk, android_package_task.gdb_setup])

    self.android_package_task = android_package_task

def copy_glue(task):
    with open(task.glue_file, 'rb') as in_f:
        with open(task.outputs[0].bldpath(task.env), 'wb') as out_f:
            out_f.write(in_f.read())

    with open(task.outputs[1].bldpath(task.env), 'wb') as out_f:
        out_f.write(ANDROID_STUB)

    return 0

task = Task.task_type_from_func('copy_glue',
                                func  = copy_glue,
                                color = 'PINK',
                                before  = 'cc cxx')

task.runnable_status = lambda self: RUN_ME

@taskgen
@before('apply_core')
@feature('apk')
def create_copy_glue(self):
    if not re.match('arm.*?android', self.env['PLATFORM']):
        return

    glue = self.path.find_or_declare('android_native_app_glue.c')
    self.allnodes.append(glue)
    stub = self.path.find_or_declare('android_stub.c')
    self.allnodes.append(stub)

    task = self.create_task('copy_glue')
    task.glue_file = '%s/android-ndk-r%s/sources/android/native_app_glue/android_native_app_glue.c' % (ANDROID_ROOT, ANDROID_NDK_VERSION)
    task.set_outputs([glue, stub])

def embed_build(task):
    symbol = task.inputs[0].name.upper().replace('.', '_').replace('-', '_').replace('@', 'at')
    in_file = open(task.inputs[0].bldpath(task.env), 'rb')
    cpp_out_file = open(task.outputs[0].bldpath(task.env), 'wb')
    h_out_file = open(task.outputs[1].bldpath(task.env), 'wb')

    cpp_str = """
#include <stdint.h>
unsigned char %s[] =
"""
    cpp_out_file.write(cpp_str % (symbol))
    cpp_out_file.write('{\n    ')

    data = in_file.read()
    for i,x in enumerate(data):
        cpp_out_file.write('0x%X, ' % ord(x))
        if i > 0 and i % 4 == 0:
            cpp_out_file.write('\n    ')
    cpp_out_file.write('\n};\n')
    cpp_out_file.write('uint32_t %s_SIZE = sizeof(%s);\n' % (symbol, symbol))

    h_out_file.write('extern unsigned char %s[];\n' % (symbol))
    h_out_file.write('extern uint32_t %s_SIZE;\n' % (symbol))

    cpp_out_file.close()
    h_out_file.close()

    m = Utils.md5()
    m.update(data)

    task.generator.bld.node_sigs[task.inputs[0].variant(task.env)][task.inputs[0].id] = m.digest()
    return 0

Task.simple_task_type('dex', '${DX} --dex --output ${TGT} ${SRC}',
                      color='YELLOW',
                      after='jar_create',
                      shell=True)

@taskgen
@after('apply_java')
@feature('dex')
def apply_dex(self):
    if not re.match('arm.*?android', self.env['PLATFORM']):
        return

    jar = self.path.find_or_declare(self.destfile)
    dex = jar.change_ext('.dex')
    task = self.create_task('dex')
    task.set_inputs(jar)
    task.set_outputs(dex)

Task.task_type_from_func('embed_file',
                         func = embed_build,
                         vars = ['SRC', 'DST'],
                         color = 'RED',
                         before  = 'cc cxx')

@feature('embed')
@before('apply_core')
def embed_file(self):
    Utils.def_attrs(self, embed_source=[])
    for name in Utils.to_list(self.embed_source):
        node = self.path.find_resource(name)
        cc_out = node.parent.find_or_declare([node.name + '.embed.cpp'])
        h_out = node.parent.find_or_declare([node.name + '.embed.h'])

        task = self.create_task('embed_file')
        task.set_inputs(node)
        task.set_outputs([cc_out, h_out])
        self.allnodes.append(cc_out)

def do_find_file(file_name, path_list):
    for directory in Utils.to_list(path_list):
        found_file_name = os.path.join(directory,file_name)
        if os.path.exists(found_file_name):
            return found_file_name

@conf
def find_file(self, file_name, path_list = [], var = None, mandatory = False):
    if not path_list: path_list = os.environ['PATH'].split(os.pathsep)
    ret = do_find_file(file_name, path_list)
    self.check_message('file', file_name, ret, ret)
    if var: self.env[var] = ret

    if not ret and mandatory:
        self.fatal('The file %s could not be found' % file_name)

    return ret

def run_gtests(valgrind = False):
    if not Options.commands['build'] or getattr(Options.options, 'skip_tests', False):
        return

# TODO: Add something similar to this
# http://code.google.com/p/v8/source/browse/trunk/tools/run-valgrind.py
# to find leaks and set error code

    if not Build.bld.env['VALGRIND']:
        valgrind = False

    for t in  Build.bld.all_task_gen:
        if hasattr(t, 'uselib') and str(t.uselib).find("GTEST") != -1:
            output = t.path
            filename = os.path.join(output.abspath(t.env), t.target)
            if valgrind:
                dynamo_home = os.getenv('DYNAMO_HOME')
                filename = "valgrind -q --leak-check=full --suppressions=%s/share/valgrind-python.supp --suppressions=%s/share/valgrind-libasound.supp --suppressions=%s/share/valgrind-libdlib.supp --error-exitcode=1 %s" % (dynamo_home, dynamo_home, dynamo_home, filename)
            proc = subprocess.Popen(filename, shell = True)
            ret = proc.wait()
            if ret != 0:
                print("test failed %s" %(t.target) )
                sys.exit(ret)

@feature('cprogram', 'cxxprogram', 'cstaticlib', 'cshlib')
@after('apply_obj_vars')
def linux_link_flags(self):
    platform = self.env['PLATFORM']
    if platform == 'linux':
        self.link_task.env.append_value('LINKFLAGS', ['-lpthread', '-lm'])

def create_clang_wrapper(conf, exe):
    clang_wrapper_path = os.path.join(conf.env['DYNAMO_HOME'], 'bin', '%s-wrapper.sh' % exe)

    s = '#!/bin/sh\n'
    # NOTE:  -Qunused-arguments to make clang happy (clang: warning: argument unused during compilation)
    s += "%s -Qunused-arguments $@\n" % os.path.join(IOS_TOOLCHAIN_ROOT, 'usr/bin/%s' % exe)
    if os.path.exists(clang_wrapper_path):
        # Keep existing script if equal
        # The cache in ccache consistency control relies on the timestamp
        with open(clang_wrapper_path, 'rb') as f:
            if f.read() == s:
                return clang_wrapper_path

    with open(clang_wrapper_path, 'wb') as f:
        f.write(s)
    os.chmod(clang_wrapper_path, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
    return clang_wrapper_path

def detect(conf):
    conf.find_program('valgrind', var='VALGRIND', mandatory = False)
    conf.find_program('ccache', var='CCACHE', mandatory = False)

    dynamo_home = os.getenv('DYNAMO_HOME')
    if not dynamo_home:
        conf.fatal("DYNAMO_HOME not set")

    conf.env['DYNAMO_HOME'] = dynamo_home

    platform = None
    if getattr(Options.options, 'platform', None):
        platform=getattr(Options.options, 'platform')

    if sys.platform == "darwin":
        build_platform = "darwin"
    elif sys.platform == "linux2":
        build_platform = "linux"
    elif sys.platform == "win32":
        build_platform = "win32"
    else:
        conf.fatal("Unable to determine host platform")

    if not platform:
        platform = build_platform

    if platform == 'darwin' or platform == 'x86_64-darwin':
        # Force gcc without llvm on darwin.
        # We got strange bugs with http cache with gcc-llvm...
        os.environ['CC'] = 'clang'
        os.environ['CXX'] = 'clang++'

    conf.env['PLATFORM'] = platform
    conf.env['BUILD_PLATFORM'] = build_platform

    if platform == "armv7-darwin":
        # Wrap clang in a bash-script due to a bug in clang related to cwd
        # waf change directory from ROOT to ROOT/build when building.
        # clang "thinks" however that cwd is ROOT instead of ROOT/build
        # This bug is at least prevalent in "Apple clang version 3.0 (tags/Apple/clang-211.12) (based on LLVM 3.0svn)"

        # NOTE: If we are to use clang for OSX-builds the wrapper script must be qualifed, e.g. clang-ios.sh or similar
        clang_wrapper = create_clang_wrapper(conf, 'clang')
        clangxx_wrapper = create_clang_wrapper(conf, 'clang++')

        conf.env['GCC-OBJCXX'] = '-xobjective-c++'
        conf.env['GCC-OBJCLINK'] = '-lobjc'
        conf.env['CC'] = clang_wrapper
        conf.env['CXX'] = clangxx_wrapper
        conf.env['LINK_CXX'] = '%s/usr/bin/clang++' % (IOS_TOOLCHAIN_ROOT)
        conf.env['CPP'] = '%s/usr/bin/clang -E' % (IOS_TOOLCHAIN_ROOT)
        conf.env['AR'] = '%s/usr/bin/ar' % (IOS_TOOLCHAIN_ROOT)
        conf.env['RANLIB'] = '%s/usr/bin/ranlib' % (IOS_TOOLCHAIN_ROOT)
        conf.env['LD'] = '%s/usr/bin/ld' % (IOS_TOOLCHAIN_ROOT)
    elif platform == "armv7-android":
        # TODO: No windows support yet (unknown path to compiler when wrote this)
        if build_platform == 'linux':
            arch = 'x86'
        else:
            arch = 'x86_64'

        bin='%s/android-ndk-r%s/toolchains/arm-linux-androideabi-%s/prebuilt/%s-%s/bin' % (ANDROID_ROOT, ANDROID_NDK_VERSION, ANDROID_GCC_VERSION, build_platform, arch)
        conf.env['CC'] = '%s/arm-linux-androideabi-gcc' % (bin)
        conf.env['CXX'] = '%s/arm-linux-androideabi-g++' % (bin)
        conf.env['LINK_CXX'] = '%s/arm-linux-androideabi-g++' % (bin)
        conf.env['CPP'] = '%s/arm-linux-androideabi-cpp' % (bin)
        conf.env['AR'] = '%s/arm-linux-androideabi-ar' % (bin)
        conf.env['RANLIB'] = '%s/arm-linux-androideabi-ranlib' % (bin)
        conf.env['LD'] = '%s/arm-linux-androideabi-ld' % (bin)

        conf.env['DX'] =  '%s/android-sdk/platform-tools/dx' % (ANDROID_ROOT)

    conf.check_tool('compiler_cc')
    conf.check_tool('compiler_cxx')

    if conf.env['CCACHE'] and not 'win32' == platform:
        if not Options.options.disable_ccache:
            # Prepend gcc/g++ with CCACHE
            for t in ['CC', 'CXX']:
                c = conf.env[t]
                if type(c) == list:
                    conf.env[t] = [conf.env.CCACHE] + c
                else:
                    conf.env[t] = [conf.env.CCACHE, c]
        else:
            Logs.info('ccache disabled')

    if platform == build_platform:
        # Host libraries are installed to $PREFIX/lib
        conf.env.BINDIR = Utils.subst_vars('${PREFIX}/bin', conf.env)
    else:
        # Cross libraries are installed to $PREFIX/lib/PLATFORM
        conf.env.BINDIR = Utils.subst_vars('${PREFIX}/bin/%s' % platform, conf.env)
        conf.env.LIBDIR = Utils.subst_vars('${PREFIX}/lib/%s' % platform, conf.env)

    if platform == "linux":
        conf.env['LIB_PLATFORM_SOCKET'] = ''
        conf.env['LIB_DL'] = 'dl'
        conf.env['LIB_UUID'] = 'uuid'
    elif 'darwin' in platform:
        conf.env['LIB_PLATFORM_SOCKET'] = ''
    elif 'android' in platform:
        conf.env['LIB_PLATFORM_SOCKET'] = ''
    else:
        conf.env['LIB_PLATFORM_SOCKET'] = 'WS2_32 Iphlpapi'.split()

def configure(conf):
    detect(conf)

old = Build.BuildContext.exec_command
def exec_command(self, cmd, **kw):
    if getattr(Options.options, 'eclipse', False):
        if isinstance(cmd, list):
            print >>sys.stderr, ' '.join(cmd)
        else:
            print >>sys.stderr, cmd
    return old(self, cmd, **kw)

Build.BuildContext.exec_command = exec_command

def set_options(opt):
    opt.tool_options('compiler_cc')
    opt.tool_options('compiler_cxx')

    opt.add_option('--eclipse', action='store_true', default=False, dest='eclipse', help='print eclipse friendly command-line')
    opt.add_option('--platform', default='', dest='platform', help='target platform, eg armv7-darwin')
    opt.add_option('--skip-tests', action='store_true', default=False, dest='skip_tests', help='skip unit tests')
    opt.add_option('--skip-codesign', action="store_true", default=False, dest='skip_codesign', help='skip code signing')
    opt.add_option('--disable-ccache', action="store_true", default=False, dest='disable_ccache', help='force disable of ccache')

