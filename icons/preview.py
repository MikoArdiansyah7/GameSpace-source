from PIL import Image, ImageDraw, ImageFont
import gen, math
S = 8      # px per unit
SS = 3     # supersampling
def render(ops, color, size=24):
    W = size*S*SS
    im = Image.new("RGBA", (W, W), (0,0,0,0)); d = ImageDraw.Draw(im)
    k = S*SS; sw = 2*k
    def pt(x, y): return (x*k, y*k)
    def rl(a, b):
        d.line([a, b], fill=color, width=int(sw))
        for p in (a, b): d.ellipse([p[0]-sw/2, p[1]-sw/2, p[0]+sw/2, p[1]+sw/2], fill=color)
    def poly_pts(v): return [pt(v[i], v[i+1]) for i in range(0, len(v), 2)]
    for op in ops:
        c = op[0]
        if c == "L": rl(pt(op[1],op[2]), pt(op[3],op[4]))
        elif c == "C":
            cx,cy,r = op[1:4]; d.ellipse([(cx-r)*k,(cy-r)*k,(cx+r)*k,(cy+r)*k], outline=color, width=int(sw))
        elif c == "D":
            cx,cy,r = op[1:4]; d.ellipse([(cx-r)*k,(cy-r)*k,(cx+r)*k,(cy+r)*k], fill=color)
        elif c == "P": d.polygon(poly_pts(op[1]), fill=color)
        elif c in ("S","Z"):
            p = poly_pts(op[1]);
            if c == "Z": p = p + [p[0]]
            for a, b in zip(p, p[1:]): rl(a, b)
        elif c in ("R","F"):
            x,y,w,h,r = op[1:6]
            if c == "R": d.rounded_rectangle([x*k,y*k,(x+w)*k,(y+h)*k], radius=r*k, outline=color, width=int(sw))
            else: d.rounded_rectangle([x*k,y*k,(x+w)*k,(y+h)*k], radius=r*k, fill=color)
        elif c == "A":
            cx,cy,r,a0,sw_ = op[1:6]
            n = max(8, int(abs(sw_)/6)); pts = []
            for i in range(n+1):
                a = math.radians(a0 + sw_*i/n); pts.append(pt(cx + r*math.cos(a), cy + r*math.sin(a)))
            for a, b in zip(pts, pts[1:]): rl(a, b)
    return im.resize((size*S, size*S), Image.LANCZOS)

names = gen.names
cols = 10; rows = (len(names)+cols-1)//cols
cell = 24*S + 40
sheet = Image.new("RGB", (cols*cell, rows*(cell+14)), (14,12,32))
dr = ImageDraw.Draw(sheet)
palette = [(139,92,246),(52,211,153),(96,165,250),(248,113,113),(251,191,36)]
for i, n in enumerate(names):
    x = (i % cols)*cell + 20; y = (i//cols)*(cell+14) + 8
    col = palette[i % 5] + (255,)
    ic = render(gen.I[n], col)
    sheet.paste(ic, (x, y), ic)
    dr.text((x, y + 24*S + 2), n, fill=(200,198,230))
sheet.save("/home/claude/icons/sheet.png"); print(sheet.size)
