import sys

with open(r"c:\Users\avare\Documents\recreate2\app\src\main\java\com\dji\recreate2\MainActivity.kt", "r", encoding="utf-8") as f:
    text = f.read()

stack = []
results = []
in_multiline_comment = False
in_string = False
escaped = False
line_num = 1
col_num = 0

i = 0
while i < len(text):
    char = text[i]
    if char == '\n':
        line_num += 1
        col_num = 0
        i += 1
        continue
    else:
        col_num += 1
    
    if in_multiline_comment:
        if text[i:i+2] == "*/":
            in_multiline_comment = False
            i += 2
            col_num += 1
            continue
        i += 1
        continue
    
    if in_string:
        if escaped:
            escaped = False
            i += 1
            continue
        if char == '\\':
            escaped = True
            i += 1
            continue
        if char == '"':
            in_string = False
            i += 1
            continue
        # Check for Kotlin string template expression ${...} inside string
        if text[i:i+2] == "${":
            stack.append(("${", line_num, col_num))
            i += 2
            col_num += 1
            continue
        i += 1
        continue

    if text[i:i+2] == "//":
        # Skip to next line
        while i < len(text) and text[i] != '\n':
            i += 1
        continue
    if text[i:i+2] == "/*":
        in_multiline_comment = True
        i += 2
        col_num += 1
        continue
    
    if char == '"':
        in_string = True
        i += 1
        continue
    
    if char == '{':
        stack.append(("{", line_num, col_num))
    elif char == '}':
        if not stack:
            print(f"Extra closing brace at Line {line_num}, Col {col_num}")
        else:
            opener, o_line, o_col = stack.pop()
            if opener == "${":
                # Closed string template
                pass
            results.append((o_line, line_num, opener))
    i += 1

print(f"Finished parsing. Unclosed braces remaining in stack: {len(stack)}")
for item in stack:
    print(f"Unclosed {item[0]} opened at Line {item[1]}, Col {item[2]}")
