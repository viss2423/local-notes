"""Probe model downloads without downloading the complete files."""
import urllib.request

files = [
    ('Whisper', 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin'),
    ('Qwen', 'https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf'),
]
for name, url in files:
    for suffix in ['', '?download=true']:
        try:
            request = urllib.request.Request(url + suffix, headers={'Range': 'bytes=0-15'})
            with urllib.request.urlopen(request, timeout=30) as response:
                print(name, suffix or 'original', response.status,
                      response.headers.get('Content-Range'), response.read(16).hex(), flush=True)
        except Exception as error:
            print(name, suffix or 'original', type(error).__name__, str(error), flush=True)
