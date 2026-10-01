"""Fetch version-matched CPU engines and public English test/model assets."""
import concurrent.futures
import json
from pathlib import Path
import urllib.request
import zipfile
import socket
import time

# Existing project evidence: this host's IPv6 route to Hugging Face resets.
_getaddrinfo = socket.getaddrinfo
socket.getaddrinfo = lambda host, port, family=0, type=0, proto=0, flags=0: _getaddrinfo(host, port, socket.AF_INET, type, proto, flags)

ROOT = Path(__file__).resolve().parents[1] / '.tools' / 'bench'
ROOT.mkdir(parents=True, exist_ok=True)
def read_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'LocalNotes-validation'}), timeout=30) as response:
        return json.load(response)
def download(name, url):
    target = ROOT / name
    if target.exists():
        print('Present', name, target.stat().st_size, flush=True)
        return target
    print('Downloading', name, flush=True)
    pending = target.with_suffix(target.suffix + '.part')
    for attempt in range(3):
        try:
            urllib.request.urlretrieve(url, pending)
            break
        except Exception as error:
            print('Retry', name, attempt + 1, str(error), flush=True)
            if attempt == 2: raise
            time.sleep(2)
    pending.replace(target)
    print('Saved', name, target.stat().st_size, flush=True)
    return target
def engine(repo, tag, needle, folder):
    info = read_json(f'https://api.github.com/repos/{repo}/releases/tags/{tag}')
    matches = [a for a in info['assets'] if a['name'] == needle]
    if not matches:
        print('Assets', repo, [a['name'] for a in info['assets']], flush=True)
        return
    asset = download(needle, matches[0]['browser_download_url'])
    with zipfile.ZipFile(asset) as archive:
        for item in archive.infolist():
            if not (ROOT / folder / item.filename).resolve().is_relative_to((ROOT / folder).resolve()):
                raise ValueError('Invalid archive path')
        archive.extractall(ROOT / folder)
    (ROOT / f'{folder}-release.json').write_text(json.dumps({'tag': tag, 'asset': matches[0]}, indent=2))

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    jobs = [pool.submit(engine, 'ggml-org/whisper.cpp', 'v1.7.6', 'whisper-bin-x64.zip', 'whisper'),
            pool.submit(engine, 'ggml-org/llama.cpp', 'b5046', 'llama-b5046-bin-win-avx2-x64.zip', 'llama')]
    for model in ['small.en', 'base.en', 'base.en-q5_1', 'tiny.en']:
        jobs.append(pool.submit(download, f'ggml-{model}.bin', f'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{model}.bin?download=true'))
    jobs.append(pool.submit(download, 'qwen-summary.gguf', 'https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true'))
    dataset = read_json('https://huggingface.co/api/datasets/hf-internal-testing/librispeech_asr_dummy/tree/main/clean?recursive=true')
    paths = [item['path'] for item in dataset if item['path'].endswith('.parquet')]
    if not paths:
        print('Dataset tree', dataset, flush=True)
    else:
        jobs.append(pool.submit(download, 'librispeech.parquet', f'https://huggingface.co/datasets/hf-internal-testing/librispeech_asr_dummy/resolve/main/{paths[0]}?download=true'))
    for job in jobs:
        try: job.result()
        except Exception as error: print('FAILED', type(error).__name__, str(error), flush=True)
