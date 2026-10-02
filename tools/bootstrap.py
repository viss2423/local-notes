"""Download portable build tools into this project; no system settings changed."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)

def fetch(item):
    name, url, destination = item
    archive = TOOLS / (name + '.zip')
    target = TOOLS / destination
    if target.exists() or (name == 'jdk' and any(TOOLS.glob('jdk-*/bin/java.exe'))):
        print('Already present:', name, flush=True)
        return
    print('Downloading:', name, flush=True)
    urllib.request.urlretrieve(url, archive)
    if name == 'gradle':
        expected = urllib.request.urlopen(url + '.sha256').read().decode().strip()
        assert hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest() == expected
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            if not (TOOLS / entry.filename).resolve().is_relative_to(TOOLS.resolve()):
                raise ValueError('Archive path escapes tool directory')
        bundle.extractall(TOOLS / 'sdk' if name == 'android-cli' else TOOLS)
    if name == 'android-cli':
        original = TOOLS / 'sdk' / 'cmdline-tools'
        original.rename(TOOLS / 'android-cli-unpacked')
        original.mkdir()
        (TOOLS / 'android-cli-unpacked').rename(original / 'latest')
    print('Ready:', name, flush=True)

with ThreadPoolExecutor(max_workers=3) as pool:
    list(pool.map(fetch, [
        ('jdk', 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip', 'jdk-17'),
        ('android-cli', 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip', 'sdk/cmdline-tools/latest'),
        ('gradle', 'https://services.gradle.org/distributions/gradle-8.9-bin.zip', 'gradle-8.9'),
    ]))

# sherpa-onnx speech engine for Android (Kotlin API + arm64 native libraries), pinned by hash.
SHERPA = ROOT / 'app/libs/sherpa-onnx-1.13.8.aar'
SHERPA_SHA256 = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
if not SHERPA.exists():
    SHERPA.parent.mkdir(parents=True, exist_ok=True)
    print('Downloading: sherpa-onnx', flush=True)
    urllib.request.urlretrieve('https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar', SHERPA)
assert hashlib.file_digest(SHERPA.open('rb'), 'sha256').hexdigest() == SHERPA_SHA256, 'sherpa-onnx AAR hash mismatch'
print('Ready: sherpa-onnx', flush=True)
