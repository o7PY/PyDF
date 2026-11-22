"""Writes an adb-shell script that draws strokes with simulated S Pen motion events."""
import math, sys

def ellipse(cx, cy, rx, ry, n=22, start=-2.2, turn=1.08):
    return [(cx + rx * math.cos(start + 2 * math.pi * turn * i / n), cy + ry * math.sin(start + 2 * math.pi * turn * i / n)) for i in range(n + 1)]

def line(a, b, n=6):
    return [(a[0] + (b[0] - a[0]) * t / n, a[1] + (b[1] - a[1]) * t / n) for t in range(n + 1)]

def quad(a, c, b, n=10):
    return [((1-t)**2*a[0] + 2*(1-t)*t*c[0] + t*t*b[0], (1-t)**2*a[1] + 2*(1-t)*t*c[1] + t*t*b[1]) for t in (i / n for i in range(n + 1))]

strokes = []
strokes.append(ellipse(505, 531, 82, 30))                       # circle "five stars."
strokes.append(quad((540, 570), (560, 700), (640, 790), 10))      # arrow shaft
strokes.append([(612, 788), (642, 792), (636, 762)])              # arrow head
x0, y0, h = 670, 800, 95                                          # "10/10"
strokes.append([(x0, y0 + 18), (x0 + 18, y0)] + line((x0 + 18, y0), (x0 + 18, y0 + h), 5))
strokes.append(ellipse(x0 + 70, y0 + h / 2, 26, h / 2, 18, -1.6, 1.02))
strokes.append(line((x0 + 135, y0), (x0 + 105, y0 + h), 4))
strokes.append([(x0 + 150, y0 + 18), (x0 + 168, y0)] + line((x0 + 168, y0), (x0 + 168, y0 + h), 5))
strokes.append(ellipse(x0 + 220, y0 + h / 2, 26, h / 2, 18, -1.6, 1.02))
cx, cy, R, r = x0 + 330, y0 + 45, 48, 20                           # star
star = []
for k in range(11):
    a = -math.pi / 2 + k * math.pi / 5
    rad = R if k % 2 == 0 else r
    star.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
strokes.append(star)
strokes.append(quad((x0 - 10, y0 + h + 30), (x0 + 200, y0 + h + 55), (x0 + 400, y0 + h + 25), 12))  # swoosh under

out = []
for s in strokes:
    (x, y), rest = s[0], s[1:]
    out.append(f"input stylus motionevent DOWN {x:.0f} {y:.0f}")
    for (x, y) in rest:
        out.append(f"input stylus motionevent MOVE {x:.0f} {y:.0f}")
    out.append(f"input stylus motionevent UP {s[-1][0]:.0f} {s[-1][1]:.0f}")
open(sys.argv[1], "w").write("\n".join(out) + "\n")
print(len(out), "events")
