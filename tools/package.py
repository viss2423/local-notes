"""Prepare an APK, clean source archive, and reproducible artifact hashes."""
from pathlib import Path
import hashlib
import shutil
import zipfile
import re

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
dist.mkdir(exist_ok=True)
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
version = re.search(r'versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text()).group(1)
apk_name = f'LocalNotes-{version}.apk'
shutil.copy2(apk, dist / apk_name)
shutil.copy2(root / 'README.md', dist / 'README.md')
shutil.copy2(root / 'BENCHMARK_REPORT.md', dist / 'BENCHMARK_REPORT.md')
shutil.copy2(root / '.tools/bench/ggml-base.en-q5_1.bin', dist / 'ggml-base.en-q5_1.bin')
previews = root / 'app/build/ui-previews'
if previews.exists():
    (dist / 'ui-previews').mkdir(exist_ok=True)
    for preview in previews.glob('*.png'):
        shutil.copy2(preview, dist / 'ui-previews' / preview.name)
excluded = {'.tools', '.gradle', '.cxx', 'build', 'dist', '__pycache__', '.kotlin', 'validation', '.git'}
with zipfile.ZipFile(dist / 'LocalNotes-source.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for file in root.rglob('*'):
        relative = file.relative_to(root)
        if file.is_file() and not (set(relative.parts) & excluded) and file.name != 'local.properties' and not file.name.endswith('-log.txt'):
            archive.write(file, 'LocalNotes/' + relative.as_posix())
with (dist / 'SHA256SUMS.txt').open('w', encoding='utf-8') as output:
    for name in [apk_name, 'LocalNotes-source.zip', 'README.md', 'BENCHMARK_REPORT.md', 'ggml-base.en-q5_1.bin']:
        with (dist / name).open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        output.write(f'{digest}  {name}\n')
        print(name, (dist / name).stat().st_size, digest)
