import urllib.request

URLS = [
    'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip',
    'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip',
    'https://raw.githubusercontent.com/ggml-org/whisper.cpp/v1.7.6/include/whisper.h',
]
for url in URLS:
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            print(response.status, response.headers.get('Content-Length'), url)
    except Exception as error:
        print(type(error).__name__, str(error), url)
