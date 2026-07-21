import sys
sys.stdout.reconfigure(encoding='utf-8')

with open(r"c:\Users\avare\Documents\recreate2\app\src\main\java\com\dji\recreate2\MainActivity.kt", "r", encoding="utf-8") as f:
    for idx, line in enumerate(f):
        if "${" in line:
            print(f"{idx+1}: {line.strip()}")
