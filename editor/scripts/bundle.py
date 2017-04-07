#!/usr/bin/env python

import os
import stat
import glob
import sys
import json
import tempfile
import urllib
import optparse
import urlparse
import re
import shutil
import subprocess
import tarfile
import zipfile
import ConfigParser
import datetime

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

def mkdirs(path):
    if not os.path.exists(path):
        os.makedirs(path)

def extract(file, path, is_mac):
    print('Extracting %s to %s' % (file, path))

    if is_mac:
        # We use the system tar command for macOSX because tar (and
        # others) on macOS has special handling of ._ files that
        # contain additional HFS+ attributes. See for example:
        # http://superuser.com/questions/61185/why-do-i-get-files-like-foo-in-my-tarball-on-os-x
        exec_command(['tar', '-C', path, '-xzf', file])
    else:
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

def exec_command(args, stdout = None, stderr = None):
    print('[EXEC] %s' % args)
    process = subprocess.Popen(args, stdout=stdout, stderr=stderr, shell = False)
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

def git_sha1(ref = 'HEAD'):
    process = subprocess.Popen(['git', 'rev-parse', ref], stdout = subprocess.PIPE)
    out, err = process.communicate()
    if process.returncode != 0:
        sys.exit(process.returncode)
    return out.strip()

def remove_readonly_retry(function, path, excinfo):
    try:
        os.chmod(path, stat.S_IWRITE)
        function(path)
    except Exception as e:
        print("Failed to remove %s, error %s" % (path, e))

def rmtree(path):
    if os.path.exists(path):
        shutil.rmtree(path, onerror=remove_readonly_retry)

def create_dmg(dmg_dir, bundle_dir, dmg_file):
    # This certificate must be installed on the computer performing the operation
    certificate = 'Developer ID Application: Midasplayer Technology AB (ATT58V7T33)'

    certificate_found = exec_command(['security', 'find-identity', '-p', 'codesigning', '-v'], stdout = subprocess.PIPE).find(certificate) >= 0

    if not certificate_found:
        print("Warning: Codesigning certificate not found, DMG will not be signed")

    # sign files in bundle
    if certificate_found:
        exec_command(['codesign', '--deep', '-s', certificate, bundle_dir])

    # create dmg
    exec_command(['hdiutil', 'create', '-volname', 'Defold', '-srcfolder', dmg_dir, dmg_file])

    # sign dmg
    if certificate_found:
        exec_command(['codesign', '-s', certificate, dmg_file])

def bundle(platform, jar_file, options):
    rmtree('tmp')

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

    if options.launcher:
        launcher = options.launcher
    else:
        launcher_version = options.git_sha1
        launcher_url = 'http://d.defold.com/archive/%s/engine/%s/launcher%s' % (launcher_version, platform_to_legacy[platform], exe_suffix)
        launcher = download(launcher_url, use_cache = False)
        if not launcher:
            print 'Failed to download launcher', launcher_url
            sys.exit(5)

    mkdirs('tmp')

    if 'darwin' in platform:
        dmg_dir = 'tmp/dmg'
        resources_dir = 'tmp/dmg/Defold.app/Contents/Resources'
        packages_dir = 'tmp/dmg/Defold.app/Contents/Resources/packages'
        bundle_dir = 'tmp/dmg/Defold.app'
        exe_dir = 'tmp/dmg/Defold.app/Contents/MacOS'
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
        shutil.copy('bundle-resources/dmg_ds_store', '%s/.DS_Store' % dmg_dir)
        shutil.copytree('bundle-resources/dmg_background', '%s/.background' % dmg_dir)
        exec_command(['ln', '-sf', '/Applications', '%s/Applications' % dmg_dir])
    if icon:
        shutil.copy('bundle-resources/%s' % icon, resources_dir)
    config = ConfigParser.ConfigParser()
    config.read('bundle-resources/config')
    config.set('build', 'sha1', options.git_sha1)
    config.set('build', 'version', options.version)
    config.set('build', 'time', datetime.datetime.now().isoformat())

    with open('%s/config' % resources_dir, 'wb') as f:
        config.write(f)

    with open('target/editor/update/config', 'wb') as f:
        config.write(f)

    shutil.copy('target/editor/update/%s' % jar_file, '%s/%s' % (packages_dir, jar_file))
    shutil.copy(launcher, '%s/Defold%s' % (exe_dir, exe_suffix))
    if not 'win32' in platform:
        exec_command(['chmod', '+x', '%s/Defold%s' % (exe_dir, exe_suffix)])

    if 'win32' in platform:
        exec_command(['java', '-cp', 'target/classes', 'com.defold.util.IconExe', '%s/Defold%s' % (exe_dir, exe_suffix), 'bundle-resources/logo.ico'])

    extract(jre, 'tmp', is_mac)

    print 'Creating bundle'
    if is_mac:
        jre_glob = 'tmp/jre1.8.0_%s.jre/Contents/Home/*' % (jre_minor)
    else:
        jre_glob = 'tmp/jre1.8.0_%s/*' % (jre_minor)

    for p in glob.glob(jre_glob):
        shutil.move(p, '%s/jre' % packages_dir)

    if is_mac:
        ziptree(bundle_dir, 'target/editor/Defold-%s.zip' % platform, dmg_dir)
    else:
        ziptree(bundle_dir, 'target/editor/Defold-%s.zip' % platform, 'tmp')

    if is_mac:
        create_dmg(dmg_dir, bundle_dir, 'target/editor/Defold-%s.dmg' % platform)

def check_reflections():
    reflection_prefix = 'Reflection warning, ' # final space important
    included_reflections = ['editor/'] # [] = include all
    ignored_reflections = []

    # lein check puts reflection warnings on stderr, redirect to stdout to capture all output
    output = exec_command(['./scripts/lein', 'check'], stdout = subprocess.PIPE, stderr = subprocess.STDOUT)
    lines = output.splitlines()
    reflection_lines = (line for line in lines if re.match(reflection_prefix, line))
    reflections = (re.match('(' + reflection_prefix + ')(.*)', line).group(2) for line in reflection_lines)
    filtered_reflections = reflections if not included_reflections else (line for line in reflections if any((re.match(include, line) for include in included_reflections)))
    failures = list(line for line in filtered_reflections if not any((re.match(ignored, line) for ignored in ignored_reflections)))

    if failures:
        for failure in failures:
            print(failure)
        exit(1)

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

    parser.add_option('--git-rev', dest='git_rev',
                      default = 'HEAD',
                      help = 'Specific git rev to use. Useful when testing bundling.')

    parser.add_option('--pack-local', dest='pack_local',
                      default = False,
                      action = 'store_true',
                      help = 'Use local artifacts when packing resources for uberjar. Useful when testing bundling.')

    parser.add_option('--launcher', dest='launcher',
                      default = None,
                      help = 'Specific local launcher. Useful when testing bundling.')

    options, all_args = parser.parse_args()

    if not options.target_platform:
        parser.error('No platform specified')

    if not options.version:
        parser.error('No version specified')

    options.git_sha1 = git_sha1(options.git_rev)
    print 'Using git rev=%s, sha1=%s' % (options.git_rev, options.git_sha1)

    rmtree('target/editor')

    print 'Building editor'

    sha1 = '' if options.pack_local else options.git_sha1
    commands = [['clean'],
                ['local-jars', sha1],
                ['builtins', sha1],
                ['protobuf'],
                ['sass', 'once'],
                ['pack', sha1]]

    for c in commands:
        exec_command(['bash', './scripts/lein', 'with-profile', '+release'] + c)
    check_reflections()
    exec_command(['./scripts/lein', 'test'])
    exec_command(['bash', './scripts/lein', 'with-profile', '+release', 'uberjar'])

    jar_file = 'defold-%s.jar' % options.git_sha1

    mkdirs('target/editor/update')
    shutil.copy('target/defold-editor-2.0.0-SNAPSHOT-standalone.jar', 'target/editor/update/%s' % jar_file)

    for platform in options.target_platform:
        bundle(platform, jar_file, options)

    package_info = {'version' : options.version,
                    'sha1' : options.git_sha1,
                    'packages': [{'url': jar_file,
                                  'platform': '*',
                                  'action': 'copy'}]}
    with open('target/editor/update/manifest.json', 'w') as f:
        f.write(json.dumps(package_info, indent=4))
