#!/usr/bin/env python

import os
import glob
import sys
import json
import tempfile
import urllib
import optparse
import urlparse
import shutil
import subprocess
import tarfile
import zipfile
import ConfigParser

# Keep this version in sync with project.clj
JOGL_VERSION = "2.0.2"
JNA_VERSION = "4.1.0"

platform_to_java = {'x86_64-linux': 'linux-x64',
                    'x86-linux': 'linux-i586',
                    'x86_64-darwin': 'macosx-x64',
                    'x86-win32': 'windows-i586',
                    'x86_64-win32': 'windows-x64'}

platform_to_legacy = {'x86_64-linux': 'x86_64-linux',
                      'x86-linux': 'linux',
                      'x86_64-darwin': 'x86_64-darwin',
                      'x86-win32': 'win32',
                      'x86_64-win32': 'x86_64-win32'}

platform_to_legacy_java = {'x86_64-linux': 'x86_64-linux',
                           'x86-linux': 'linux',
                           'x86_64-darwin': 'x86_64-darwin',
                           'x86-win32': 'win32',
                           'x86_64-win32': 'x86_64-win32'}

platform_to_jogl = {'x86_64-linux': 'linux-amd64',
                    'x86-linux': 'linux-i586',
                    'x86_64-darwin': 'macosx-universal',
                    'x86-win32': 'windows-i586',
                    'x86_64-win32': 'windows-amd64'}

def mkdirs(path):
    if not os.path.exists(path):
        os.makedirs(path)

def extract(file, path):
    print('Extracting %s to %s' % (file, path))
    tf = tarfile.TarFile.open(file, 'r:gz')
    tf.extractall(path)
    tf.close()

def download(url, use_cache = True):
    name = os.path.basename(urlparse.urlparse(url).path)
    if use_cache:
        path = os.path.expanduser('~/.dcache/%s' % name)
        if os.path.exists(path):
            return path
    else:
        t = tempfile.mkdtemp()
        path = '%s/%s' % (t, name)

    if not os.path.exists(os.path.dirname(path)):
        os.makedirs(os.path.dirname(path), 0755)

    tmp = path + '_tmp'
    with open(tmp, 'wb') as f:
        print('Downloading %s %d%%' % (name, 0))
        x = urllib.urlopen(url)
        if x.code != 200:
            return None
        file_len = int(x.headers.get('Content-Length', 0))
        buf = x.read(1024 * 1024)
        n = 0
        while buf:
            n += len(buf)
            print('Downloading %s %d%%' % (name, 100 * n / file_len))
            f.write(buf)
            buf = x.read(1024 * 1024)

    if os.path.exists(path): os.unlink(path)
    os.rename(tmp, path)
    return path

def exec_command(args):
    process = subprocess.Popen(args, stdout = subprocess.PIPE, shell = True)

    output = process.communicate()[0]
    if process.returncode != 0:
        print(output)
        sys.exit(process.returncode)
    return output

def ziptree(path, outfile, directory = None):
    # Directory is similar to -C in tar

    zip = zipfile.ZipFile(outfile, 'w')
    for root, dirs, files in os.walk(path):
        for f in files:
            p = os.path.join(root, f)
            an = p
            if directory:
                an = os.path.relpath(p, directory)
            zip.write(p, an)

    zip.close()
    return outfile

def add_native_libs(platform, in_jar_name, out_jar):
    with zipfile.ZipFile(in_jar_name, 'r') as in_jar:
        for zi in in_jar.infolist():
            if not 'META-INF' in zi.filename:
                d = in_jar.read(zi)
                n = 'lib/%s/%s' % (platform, zi.filename)
                print "adding", n
                out_jar.writestr(n, d)

def download_artifacts(platforms, ext_lib_path):
    sha1 = git_sha1()
    base_url = 'http://d.defold.com/archive/%s/engine' % (sha1)

    artifacts = {'x86_64-darwin' : {'exes' : ['dmengine', 'dmengine_release'],
                                    'libs' : ['libparticle_shared.dylib', 'libtexc_shared.dylib'],
                                    'ext_libs' : [] },
                 'x86-win32' : {'exes' : ['dmengine.exe', 'dmengine_release.exe'],
                                'libs' : ['particle_shared.dll', 'texc_shared.dll'],
                                'ext_libs' : ['OpenAL32.dll'] },
                 'x86-linux' : {'exes' : ['dmengine', 'dmengine_release'],
                                'libs' : ['libparticle_shared.so', 'libtexc_shared.so'],
                                'ext_libs' : [] },
                 'x86_64-linux' : {'exes' : ['dmengine', 'dmengine_release'],
                                   'libs' : ['libparticle_shared.so', 'libtexc_shared.so'],
                                   'ext_libs' : [] }}

    for p in artifacts.keys():
        print('Downloading for %s' % (p))
        if p in platforms:
            def download_lib(lib):
                url = '%s/%s/%s' % (base_url, platform_to_legacy_java[p], lib)
                return (lib, download(url, use_cache = False))
            artifacts[p]['libs'] = map(download_lib, artifacts[p]['libs'])
        else:
            artifacts[p]['libs'] = []

        def download_exe(exe):
            url = '%s/%s/%s' % (base_url, platform_to_legacy_java[p], exe)
            return (exe, download(url, use_cache = False))
        artifacts[p]['exes'] = map(download_exe, artifacts[p]['exes'])

        def fetch_ext_lib(ext_lib):
            return (ext_lib, '%s/%s/%s' % (ext_lib_path, platform_to_legacy[p], ext_lib))
        artifacts[p]['ext_libs'] = map(fetch_ext_lib, artifacts[p]['ext_libs'])

    return artifacts

def unlink_artifacts(artifacts):
    for p in artifacts.keys():
        for l in artifacts[p]['libs']:
            os.unlink(l[1])

        for e in artifacts[p]['exes']:
            os.unlink(e[1])

def bundle_natives(artifacts, in_jar_name, out_jar_name):
    with zipfile.ZipFile(out_jar_name, 'w') as out_jar:

        for platform in ['x86-linux', 'x86_64-linux', 'x86_64-darwin', 'x86-win32']:
            jogl_platform = platform_to_jogl[platform]
            add_native_libs(platform, os.path.expanduser("~/.m2/repository/org/jogamp/jogl/jogl-all/%s/jogl-all-%s-natives-%s.jar" % (JOGL_VERSION, JOGL_VERSION, jogl_platform)), out_jar)
            add_native_libs(platform, os.path.expanduser("~/.m2/repository/org/jogamp/gluegen/gluegen-rt/%s/gluegen-rt-%s-natives-%s.jar" % (JOGL_VERSION, JOGL_VERSION, jogl_platform)), out_jar)

        with zipfile.ZipFile(os.path.expanduser("~/.m2/repository/net/java/dev/jna/jna/%s/jna-%s.jar" % (JNA_VERSION, JNA_VERSION)), 'r') as in_jar:
            for jna_p, p, lib in [("darwin", "x86_64-darwin", "libjnidispatch.jnilib"),
                                  ("win32-x86", "x86-win32", "jnidispatch.dll"),
                                  ("linux-x86", "x86-linux", "libjnidispatch.so"),
                                  ("linux-x86-64", "x86_64-linux", "libjnidispatch.so")]:
                d = in_jar.read('com/sun/jna/%s/%s' % (jna_p, lib))
                n = 'lib/%s/%s' % (p, lib)
                print "adding", n
                out_jar.writestr(n, d)

        for p in artifacts.keys():
            for l in artifacts[p]['libs']:
                out_jar.write(l[1], 'lib/%s/%s' % (p, l[0]))

            for e in artifacts[p]['exes']:
                out_jar.write(e[1], 'libexec/%s/%s' % (p, e[0]))

            for e in artifacts[p]['ext_libs']:
                out_jar.write(e[1], 'libexec/%s/%s' % (p, e[0]))

        with zipfile.ZipFile(in_jar_name, 'r') as in_jar:
            for zi in in_jar.infolist():
                if not (zi.filename.endswith('.so') or zi.filename.endswith('.dll') or zi.filename.endswith('.jnilib')):
                    d = in_jar.read(zi)
                    out_jar.writestr(zi, d)

def git_sha1(ref = None):
    args = 'git log --pretty=%H -n1'.split()
    if ref:
        args.append(ref)
    process = subprocess.Popen(args, stdout = subprocess.PIPE)
    out, err = process.communicate()
    if process.returncode != 0:
        sys.exit(process.returncode)

    line = out.split('\n')[0].strip()
    sha1 = line.split()[0]
    return sha1

def bundle(platform, jar_file, options):
    if os.path.exists('tmp'):
        shutil.rmtree('tmp')

    jre_minor = 102
    ext = 'tar.gz'
    jre_url = 'https://s3-eu-west-1.amazonaws.com/defold-packages/jre-8u%d-%s.%s' % (jre_minor, platform_to_java[platform], ext)
    jre = download(jre_url)
    if not jre:
        print('Failed to download %s' % jre_url)
        sys.exit(5)

    exe_suffix = ''
    if 'win32' in platform:
        exe_suffix = '.exe'
    sha1 = git_sha1()

    if options.launcher:
        launcher = options.launcher
    else:
        launcher_version = sha1
        launcher_url = 'http://d.defold.com/archive/%s/engine/%s/launcher%s' % (launcher_version, platform_to_legacy[platform], exe_suffix)
        launcher = download(launcher_url, use_cache = False)
        if not launcher:
            print 'Failed to download launcher', launcher_url
            sys.exit(5)

    mkdirs('tmp')

    if 'darwin' in platform:
        resources_dir = 'tmp/Defold.app/Contents/Resources'
        packages_dir = 'tmp/Defold.app/Contents/Resources/packages'
        bundle_dir = 'tmp/Defold.app'
        exe_dir = 'tmp/Defold.app/Contents/MacOS'
        icon = 'logo.icns'
        is_mac = True
    else:
        resources_dir = 'tmp/Defold'
        packages_dir = 'tmp/Defold/packages'
        bundle_dir = 'tmp/Defold'
        exe_dir = 'tmp/Defold'
        icon = None
        is_mac = False

    mkdirs(exe_dir)
    mkdirs(resources_dir)
    mkdirs(packages_dir)
    mkdirs('%s/jre' % packages_dir)

    if is_mac:
        shutil.copy('bundle-resources/Info.plist', '%s/Contents' % bundle_dir)
    if icon:
        shutil.copy('bundle-resources/%s' % icon, resources_dir)
    config = ConfigParser.ConfigParser()
    config.read('bundle-resources/config')
    config.set('build', 'sha1', sha1)
    config.set('build', 'version', options.version)

    with open('%s/config' % resources_dir, 'wb') as f:
        config.write(f)

    with open('target/editor/update/config', 'wb') as f:
        config.write(f)

    shutil.copy('target/editor/update/%s' % jar_file, '%s/%s' % (packages_dir, jar_file))
    shutil.copy(launcher, '%s/Defold%s' % (exe_dir, exe_suffix))
    exec_command('chmod +x %s/Defold%s' % (exe_dir, exe_suffix))

    extract(jre, 'tmp')
    print 'Creating bundle'
    if is_mac:
        jre_glob = 'tmp/jre1.8.0_%s.jre/Contents/Home/*' % (jre_minor)
    else:
        jre_glob = 'tmp/jre1.8.0_%s/*' % (jre_minor)

    for p in glob.glob(jre_glob):
        shutil.move(p, '%s/jre' % packages_dir)

    ziptree(bundle_dir, 'target/editor/Defold-%s.zip' % platform, 'tmp')

if __name__ == '__main__':
    usage = '''usage: %prog [options] command(s)'''

    parser = optparse.OptionParser(usage)

    parser.add_option('--platform', dest='target_platform',
                      default = None,
                      action = 'append',
                      choices = ['x86_64-linux', 'x86-linux', 'x86_64-darwin', 'x86-win32', 'x86_64-win32'],
                      help = 'Target platform. Specify multiple times for multiple platforms')

    parser.add_option('--version', dest='version',
                      default = None,
                      help = 'Version')

    parser.add_option('--launcher', dest='launcher',
                      default = None,
                      help = 'Specific local launcher. Useful when testing bundling.')

    parser.add_option('--ext-lib-path', dest='ext_lib_path',
                      default = None,
                      help = 'Where to look for external libraries, e.g. $DYNAMO_HOME/ext/lib')

    options, all_args = parser.parse_args()

    if not options.target_platform:
        parser.error('No platform specified')

    if not options.version:
        parser.error('No version specified')

    if not options.ext_lib_path:
        parser.error('No ext-lib-path specified')

    if os.path.exists('target/editor'):
        shutil.rmtree('target/editor')

    print 'Building editor'
    exec_command('./scripts/lein clean')
    exec_command('./scripts/lein with-profile +release uberjar')

    sha1 = git_sha1()
    artifacts = download_artifacts(options.target_platform, options.ext_lib_path)
    mkdirs('target/editor/update')
    jar_file = 'defold-%s.jar' % sha1
    bundle_natives(artifacts, 'target/defold-editor-2.0.0-SNAPSHOT-standalone.jar', 'target/editor/update/%s' % jar_file)
    unlink_artifacts(artifacts)
    for platform in options.target_platform:
        bundle(platform, jar_file, options)

    package_info = {'version' : options.version,
                    'sha1' : sha1,
                    'packages': [{'url': 'defold-%s.jar' % sha1,
                                  'platform': '*',
                                  'action': 'copy'}]}
    with open('target/editor/update/manifest.json', 'w') as f:
        f.write(json.dumps(package_info, indent=4))
