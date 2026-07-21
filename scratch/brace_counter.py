import sys

with open(r"c:\Users\avare\Documents\recreate2\app\src\main\java\com\dji\recreate2\MainActivity.kt", "r", encoding="utf-8") as f:
    lines = f.readlines()

nesting = 0
in_comment = False
in_multiline_comment = False

# We want to trace nesting level changes.
history = []

for idx, line in enumerate(lines):
    line_num = idx + 1
    # Parse line
    i = 0
    start_nesting = nesting
    while i < len(line):
        if in_multiline_comment:
            if line[i:i+2] == "*/":
                in_multiline_comment = False
                i += 2
                continue
            i += 1
            continue
        if line[i:i+2] == "//":
            break # rest of line is comment
        if line[i:i+2] == "/*":
            in_multiline_comment = True
            i += 2
            continue
        
        char = line[i]
        if char == '"':
            # Skip string literal
            i += 1
            while i < len(line) and line[i] != '"':
                if line[i] == '\\':
                    i += 2
                else:
                    i += 1
            i += 1
            continue
        elif char == "'":
            # Skip char literal
            i += 1
            while i < len(line) and line[i] != "'":
                if line[i] == '\\':
                    i += 2
                else:
                    i += 1
            i += 1
            continue
        elif char == '{':
            nesting += 1
        elif char == '}':
            nesting -= 1
        i += 1
    
    if nesting != start_nesting or "fun " in line:
        history.append((line_num, start_nesting, nesting, line.strip()))

# Print only the lines around where nesting got higher than expected (e.g. above 2 or 3 in class scope)
# Let's print the last 150 events
for item in history[-250:]:
    print(f"Line {item[0]:4d}: Nesting {item[1]} -> {item[2]} | {item[3][:100]}")
