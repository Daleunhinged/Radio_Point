"""Render local OSM geometry to raster MBTiles, without fetching map tiles.
Dependencies: Pillow, shapely. Input: areas.json and <area>.json from fetch.py.
"""
import json,math,sqlite3,io,hashlib,zipfile,shutil
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
from shapely.geometry import LineString,Point,box,mapping
from shapely.strtree import STRtree
P=Path(__file__).parent; OUT=P/'Quesnel-Road-Maps';OUT.mkdir(exist_ok=True)
AREAS=json.loads((P/'areas.json').read_text())
FONT='/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
def ft(n):return ImageFont.truetype(FONT,n)
def merc(lon,lat):return ((lon+180)/360,(1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2)
def style(t,z):
 h=t.get('highway','')
 if h in ('motorway','trunk','primary','secondary'):return ('#d4a353',max(2,z-9),'#716449')
 if h in ('tertiary','unclassified','residential','living_street'):return ('#faf8ef',max(1.2,(z-9)*.6),'#9d9d86')
 if h in ('track','service'):return ('#a69072',max(.8,(z-10)*.4),None)
 return ('#b6aba0',.8,None)
def setup(name):
 j=json.loads((P/(name+'.json')).read_text());recs=[];geo=[]
 for e in j['elements']:
  t=e.get('tags',{})
  if e['type']=='node':coords=[(e['lon'],e['lat'])];g=Point(merc(*coords[0]))
  else:
   coords=[(v['lon'],v['lat']) for v in e.get('geometry',[]) if 'lat' in v]
   if len(coords)<2:continue
   g=LineString([merc(*c) for c in coords])
  recs.append((g,t,e['type'],e['id']));geo.append({'type':'Feature','id':f"{e['type']}/{e['id']}",'properties':t,'geometry':{'type':'Point','coordinates':coords[0]} if e['type']=='node' else {'type':'LineString','coordinates':coords}})
 tree=STRtree([r[0] for r in recs]);return j,recs,tree,geo

def draw_region(recs,tree,z,x,y,width=256,height=256,credit=True):
 scale=256*2**z;left=x*256;top=y*256
 im=Image.new('RGB',(width,height),'#edf0e4');d=ImageDraw.Draw(im)
 inds=tree.query(box((left-30)/scale,(top-30)/scale,(left+width+30)/scale,(top+height+30)/scale))
 rs=[recs[int(i)] for i in inds];rs.sort(key=lambda r:0 if 'highway' not in r[1] else 1 if r[1]['highway'] in ('track','path','service','footway') else 2)
 labels=[]
 for g,t,typ,ident in rs:
  if typ=='node':
   if z>=12 or t.get('place') in ('city','town','village','hamlet'):
    xx,yy=g.x*scale-left,g.y*scale-top;d.ellipse((xx-2,yy-2,xx+2,yy+2),fill='#364a40');labels.append((xx+5,yy-8,t.get('name',''),14 if t.get('place') in ('city','town','village') else 12 if t.get('place')=='hamlet' else 10,'#263d35'))
   continue
  pts=[(a*scale-left,b*scale-top) for a,b in g.coords]
  if t.get('natural')=='water' and g.is_ring:d.polygon(pts,fill='#bfdce1');continue
  if 'waterway' in t:d.line(pts,fill='#a9ceda',width=max(1,z-10));continue
  h=t.get('highway')
  if not h:continue
  if z<=10 and h in ('path','footway','cycleway','steps','service','residential'):continue
  if h in ('proposed','construction'):continue
  color,w,casing=style(t,z);w=max(1,round(w))
  if casing:d.line(pts,fill=casing,width=w+2)
  d.line(pts,fill=color,width=w)
  name=t.get('name') or t.get('ref')
  if name and (z>=13 or (z>=10 and h in ('trunk','primary','secondary','tertiary'))):
   # Place labels on the visible section, not at a distant way midpoint.
   vis=g.intersection(box(left/scale,top/scale,(left+width)/scale,(top+height)/scale))
   if not vis.is_empty and vis.length*scale>85:
    pt=vis.interpolate(.5,normalized=True);labels.append((pt.x*scale-left,pt.y*scale-top,name,10,'#4f5147'))
 used=[]
 for xx,yy,label,size,color in sorted(labels,key=lambda a:-a[3]):
  if not label:continue
  font=ft(size);bb=d.textbbox((xx,yy),label,font=font,stroke_width=1);bb=(bb[0]-3,bb[1]-3,bb[2]+3,bb[3]+3)
  if bb[0]<2 or bb[2]>width-2 or bb[1]<2 or bb[3]>height-16:continue
  if any(not(bb[2]<b[0] or bb[0]>b[2] or bb[3]<b[1] or bb[1]>b[3]) for b in used):continue
  d.text((xx,yy),label,font=font,fill=color,stroke_width=2,stroke_fill='#edf0e4');used.append(bb)
 if credit:
  d.rectangle((0,height-12,width,height),fill='#f9faf4');d.text((4,height-12),'© OpenStreetMap contributors · ODbL',font=ft(9),fill='#526058')
 return im

summary=[]
for name,bounds in AREAS.items():
 print('Rendering',name,flush=True)
 j,recs,tree,geo=setup(name);w,s,e,n=bounds
 fn=OUT/(name+'.mbtiles')
 if fn.exists():fn.unlink()
 db=sqlite3.connect(fn);db.executescript('CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT);CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row));')
 meta={'name':name+' | RadioPoint roads','format':'png','type':'baselayer','version':'1.3','description':'Road reference map; no terrain, live closures or access verification. © OpenStreetMap contributors.','attribution':'© OpenStreetMap contributors | https://www.openstreetmap.org/copyright','bounds':','.join(map(str,bounds)),'center':f'{(w+e)/2},{(s+n)/2},10','minzoom':'8','maxzoom':'14','scheme':'tms'}
 db.executemany('INSERT INTO metadata VALUES (?,?)',meta.items());count=0
 for z in range(8,15):
  nw=merc(w,n);se=merc(e,s);N=2**z
  for x in range(int(nw[0]*N),int(se[0]*N)+1):
   for y in range(int(nw[1]*N),int(se[1]*N)+1):
    im=draw_region(recs,tree,z,x,y);buf=io.BytesIO();im.save(buf,format='PNG',optimize=True)
    db.execute('INSERT INTO tiles VALUES (?,?,?,?)',(z,x,N-1-y,buf.getvalue()));count+=1
  db.commit();print(name,'zoom',z,'tiles',count,flush=True)
 assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok';db.close()
 # A continuous overview for visual review, with a clear legend.
 z=10;nw=merc(w,n);se=merc(e,s);x=nw[0]*2**z;y=nw[1]*2**z;W=round((se[0]-nw[0])*256*2**z);H=round((se[1]-nw[1])*256*2**z)
 preview=draw_region(recs,tree,z,x,y,W,H)
 canvas=Image.new('RGB',(W,max(H+110,200)),'#f9faf4');canvas.paste(preview,(0,65));d=ImageDraw.Draw(canvas);d.text((15,12),name.replace('-',' – '),font=ft(24),fill='#263d35');d.text((15,43),'Offline road reference • zoom 8–14 • no terrain or live road conditions',font=ft(12),fill='#526058');d.text((15,H+72),'Gold: main roads   White: local roads   Brown/grey: tracks & minor ways   Blue: water',font=ft(11),fill='#526058');canvas.save(OUT/(name+'-preview.png'))
 (OUT/(name+'-source.geojson')).write_text(json.dumps({'type':'FeatureCollection','name':name,'attribution':'© OpenStreetMap contributors','license':'https://opendatacommons.org/licenses/odbl/1-0/','features':geo},separators=(',',':')))
 summary.append({'area':name,'bounds_wsen':bounds,'source_timestamp':j.get('osm3s',{}).get('timestamp_osm_base'),'features':len(recs),'tiles':count,'bytes':fn.stat().st_size,'sha256':hashlib.sha256(fn.read_bytes()).hexdigest()})
(OUT/'manifest.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2))
