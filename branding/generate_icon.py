"""Generate the flat vector DataBridge mark and Windows icon sizes (Pillow)."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
BRAND = ROOT / "branding"
SVG = '''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" role="img" aria-labelledby="title">
<title id="title">DataBridge — connected data</title>
<rect x="8" y="8" width="240" height="240" rx="56" fill="#3447AE"/>
<path d="M58 182V148C58 60 198 60 198 148V182" fill="none" stroke="#F7FAFF" stroke-width="14" stroke-linecap="round"/>
<path d="M128 98V150" fill="none" stroke="#A9BDF5" stroke-width="8" stroke-linecap="round"/>
<path d="M76 160H178M166 148L178 160L166 172" fill="none" stroke="#76E0D1" stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>
<rect x="40" y="142" width="36" height="36" rx="10" fill="#F7FAFF"/>
<rect x="180" y="142" width="36" height="36" rx="10" fill="#F7FAFF"/>
<path d="M50 154H66M50 164H61M190 154H206M190 164H201" fill="none" stroke="#3447AE" stroke-width="4" stroke-linecap="round"/>
<rect x="112" y="66" width="32" height="32" rx="10" fill="#FFAD86"/>
</svg>'''
BRAND.mkdir(exist_ok=True)
(BRAND / "databridge.svg").write_text(SVG, encoding="utf-8")
public = ROOT / "frontend/public"
public.mkdir(exist_ok=True)
(public / "databridge.svg").write_text(SVG, encoding="utf-8")
K = 4
im = Image.new("RGBA", (256*K, 256*K))
d = ImageDraw.Draw(im)
def box(values): return tuple(round(x*K) for x in values)
def rounded(bounds, radius, fill): d.rounded_rectangle(box(bounds), radius=radius*K, fill=fill)
def line(points, fill, width):
    d.line([(round(x*K), round(y*K)) for x,y in points], fill=fill, width=width*K, joint="curve")
    r = width/2
    for x,y in (points[0], points[-1]): d.ellipse(box((x-r,y-r,x+r,y+r)), fill=fill)
rounded((8,8,248,248), 56, "#3447AE")
points = [(58,182),(58,148)]
for i in range(201):
    t=i/200; u=1-t
    points.append((u**3*58+3*u*u*t*58+3*u*t*t*198+t**3*198,
                   u**3*148+3*u*u*t*60+3*u*t*t*60+t**3*148))
points.append((198,182))
line(points,"#F7FAFF",14)
line([(128,98),(128,150)],"#A9BDF5",8)
line([(76,160),(178,160)],"#76E0D1",10)
line([(166,148),(178,160),(166,172)],"#76E0D1",10)
for x in (40,180):
    rounded((x,142,x+36,178),10,"#F7FAFF")
    line([(x+10,154),(x+26,154)],"#3447AE",4)
    line([(x+10,164),(x+21,164)],"#3447AE",4)
rounded((112,66,144,98),10,"#FFAD86")
im = im.resize((256,256),Image.Resampling.LANCZOS)
im.save(BRAND / "databridge-256.png")
im.save(BRAND / "databridge.ico",sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
resources = ROOT / "app/src/main/resources/icons"
resources.mkdir(parents=True,exist_ok=True)
for n in (16,32,48,256): im.resize((n,n),Image.Resampling.LANCZOS).save(resources/f"databridge-{n}.png")
sheet = Image.new("RGB",(620,330),"#F3F5FA")
for n,x in ((256,20),(64,320),(48,415),(32,500),(16,575)):
    icon = im.resize((n,n),Image.Resampling.LANCZOS)
    sheet.paste(icon,(x,30),icon)
    ImageDraw.Draw(sheet).text((x,305),f"{n}px",fill="#58667B")
sheet.save(BRAND / "icon-preview.png")
