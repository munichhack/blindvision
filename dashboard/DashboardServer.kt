package com.blindvision.planning.dashboard

import com.blindvision.planning.BuildingGrid
import com.blindvision.planning.BuildingPos
import com.blindvision.planning.CellType
import com.blindvision.planning.MockBuilding
import com.blindvision.planning.Route
import com.blindvision.planning.RoutePlanner
import com.blindvision.planning.TransitionSegment
import com.blindvision.planning.WalkSegment
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A tiny zero-dependency dashboard for the planning module.
 *
 * JDK-only (`com.sun.net.httpserver`), so it lives OUTSIDE the Android source set
 * (this API is not in the Android SDK) and edits nothing existing. It serves a
 * static canvas UI and wraps the real [RoutePlanner] — the same code the app
 * uses — so what you see is what the planner actually produces.
 *
 * Run:
 *   kotlinc app/src/main/java/com/blindvision/planning/ dashboard/DashboardServer.kt -include-runtime -d /tmp/dashboard.jar
 *   java -cp /tmp/dashboard.jar com.blindvision.planning.dashboard.DashboardServerKt 8080
 *   open http://localhost:8080
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8080
    val building = MockBuilding.build()
    val planner = RoutePlanner(building)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/building") { ex -> respond(ex, "application/json", buildingJson(building)) }
    server.createContext("/plan") { ex -> handlePlan(ex, planner) }
    server.createContext("/") { ex -> respond(ex, "text/html; charset=utf-8", INDEX_HTML) }
    server.executor = null
    server.start()
    println("Dashboard running:  http://localhost:$port")
    println("(Ctrl+C to stop)")
}

private fun respond(ex: HttpExchange, contentType: String, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    ex.responseHeaders.add("Content-Type", contentType)
    ex.sendResponseHeaders(200, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun query(ex: HttpExchange): Map<String, String> =
    ex.requestURI.rawQuery?.split("&")?.mapNotNull {
        val p = it.split("="); if (p.size == 2) p[0] to p[1] else null
    }?.toMap() ?: emptyMap()

private fun handlePlan(ex: HttpExchange, planner: RoutePlanner) {
    val q = query(ex)
    fun i(k: String) = q[k]?.toIntOrNull()
    val sf = i("sf"); val sx = i("sx"); val sy = i("sy")
    val tf = i("tf"); val tx = i("tx"); val ty = i("ty")
    if (sf == null || sx == null || sy == null || tf == null || tx == null || ty == null) {
        respond(ex, "application/json", "{\"ok\":false,\"error\":\"missing params\"}")
        return
    }
    val route = planner.plan(BuildingPos(sf, sx, sy), BuildingPos(tf, tx, ty))
    respond(ex, "application/json", planJson(route))
}

private fun buildingJson(b: BuildingGrid): String {
    val sb = StringBuilder()
    sb.append("{\"floors\":[")
    b.floors.forEachIndexed { fi, f ->
        if (fi > 0) sb.append(",")
        sb.append("{\"index\":").append(f.index)
            .append(",\"width\":").append(f.width)
            .append(",\"height\":").append(f.height)
            .append(",\"rows\":[")
        for (y in 0 until f.height) {
            if (y > 0) sb.append(",")
            sb.append("\"")
            for (x in 0 until f.width) {
                sb.append(
                    when (f.typeAt(x, y)) {
                        CellType.WALKABLE -> '.'
                        CellType.NON_WALKABLE -> '#'
                        CellType.PORTAL -> 'E'
                    }
                )
            }
            sb.append("\"")
        }
        sb.append("]}")
    }
    sb.append("],\"portals\":[")
    var first = true
    for (r in b.portalRegions) {
        for (c in r.cells) {
            if (!first) sb.append(",")
            first = false
            sb.append("{\"floor\":").append(r.floor)
                .append(",\"x\":").append(c.x)
                .append(",\"y\":").append(c.y)
                .append(",\"shaft\":").append(r.shaftId).append("}")
        }
    }
    sb.append("]}")
    return sb.toString()
}

private fun planJson(route: Route?): String {
    if (route == null) return "{\"ok\":false}"
    val sb = StringBuilder()
    sb.append("{\"ok\":true,\"walkCells\":").append(route.walkCells)
        .append(",\"rides\":").append(route.floorChanges)
        .append(",\"segments\":[")
    route.segments.forEachIndexed { i, s ->
        if (i > 0) sb.append(",")
        when (s) {
            is WalkSegment -> {
                sb.append("{\"type\":\"walk\",\"floor\":").append(s.floor).append(",\"path\":[")
                s.path.forEachIndexed { j, p ->
                    if (j > 0) sb.append(",")
                    sb.append("[").append(p.x).append(",").append(p.y).append("]")
                }
                sb.append("]}")
            }
            is TransitionSegment -> {
                sb.append("{\"type\":\"ride\",\"fromFloor\":").append(s.fromFloor)
                    .append(",\"toFloor\":").append(s.toFloor)
                    .append(",\"x\":").append(s.at.x)
                    .append(",\"y\":").append(s.at.y)
                    .append(",\"shaft\":").append(s.shaftId).append("}")
            }
        }
    }
    sb.append("]}")
    return sb.toString()
}

// Embedded single-page UI. No '$' and no triple-quote inside, so it is a safe Kotlin raw string.
private val INDEX_HTML = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>BlindVision Planner</title>
<style>
 body{font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:0;background:#0f141a;color:#e6edf3}
 header{padding:14px 20px;background:#161b22;border-bottom:1px solid #283038}
 h1{font-size:16px;margin:0}
 .controls{display:flex;gap:18px;align-items:flex-end;flex-wrap:wrap;padding:14px 20px;background:#11161c;border-bottom:1px solid #283038}
 .grp{display:flex;flex-direction:column;gap:4px}
 .grp > label{font-size:11px;color:#8b949e;text-transform:uppercase;letter-spacing:.04em}
 .row{display:flex;gap:6px}
 input{width:54px;background:#0d1117;border:1px solid #30363d;color:#e6edf3;border-radius:6px;padding:6px;font-size:13px}
 button{background:#238636;color:#fff;border:0;border-radius:6px;padding:8px 16px;font-weight:600;cursor:pointer}
 button.sec{background:#30363d}
 #status{font-size:13px;color:#9aa4ae;margin-left:auto;max-width:420px}
 #floors{padding:20px;display:flex;flex-direction:column;gap:26px}
 .floor h2{font-size:13px;color:#adbac7;margin:0 0 8px}
 canvas{background:#0d1117;border:1px solid #283038;border-radius:8px;cursor:crosshair}
 .legend{font-size:12px;color:#8b949e;display:flex;gap:16px;padding:0 20px 4px;flex-wrap:wrap}
 .legend span{display:inline-flex;align-items:center;gap:6px}
 .sw{width:12px;height:12px;border-radius:3px;display:inline-block}
</style>
</head>
<body>
<header><h1>BlindVision &middot; Multi-floor Path Planner</h1></header>
<div class="controls">
  <div class="grp"><label>Start (floor, x, y)</label>
    <div class="row"><input id="sf" placeholder="f"><input id="sx" placeholder="x"><input id="sy" placeholder="y"></div></div>
  <div class="grp"><label>Target (floor, x, y)</label>
    <div class="row"><input id="tf" placeholder="f"><input id="tx" placeholder="x"><input id="ty" placeholder="y"></div></div>
  <button onclick="plan()">Plan route</button>
  <button class="sec" onclick="clearRoute()">Clear</button>
  <span id="status">Click a cell to set start, then target &mdash; or type coordinates &mdash; then Plan.</span>
</div>
<div class="legend">
  <span><i class="sw" style="background:#e8edf2"></i>walkable</span>
  <span><i class="sw" style="background:#2b3440"></i>wall</span>
  <span><i class="sw" style="background:#6aa9ff"></i>portal (elevator/stairs)</span>
  <span><i class="sw" style="background:#ff8c00"></i>walk path</span>
  <span><i class="sw" style="background:#b07cff"></i>ride</span>
  <span><i class="sw" style="background:#2ecc71"></i>start</span>
  <span><i class="sw" style="background:#e5484d"></i>target</span>
</div>
<div id="floors"></div>
<script>
var CS=26, building=null, segments=null, clickTarget=false;
function el(id){return document.getElementById(id);}
function val(id){var v=parseInt(el(id).value);return isNaN(v)?null:v;}

fetch('/building').then(function(r){return r.json();}).then(function(b){building=b;renderFloors();maybeAutoPlan();});

function maybeAutoPlan(){
  var q=new URLSearchParams(window.location.search);
  var ids=['sf','sx','sy','tf','tx','ty'], all=true;
  ids.forEach(function(k){ if(q.get(k)===null) all=false; else el(k).value=q.get(k); });
  if(all) plan();
}

function renderFloors(){
  var host=el('floors'); host.innerHTML='';
  building.floors.forEach(function(f){
    var wrap=document.createElement('div'); wrap.className='floor';
    var h=document.createElement('h2'); h.textContent='Floor '+f.index+'  ('+f.width+' x '+f.height+')';
    var cv=document.createElement('canvas');
    cv.width=f.width*CS; cv.height=f.height*CS; cv.dataset.floor=f.index;
    cv.addEventListener('click', function(ev){onCellClick(ev,f);});
    wrap.appendChild(h); wrap.appendChild(cv); host.appendChild(wrap);
    drawFloor(f,cv);
  });
  if(segments) drawOverlay();
}
function floorCanvas(idx){
  var cvs=document.querySelectorAll('canvas');
  for(var i=0;i<cvs.length;i++){ if(parseInt(cvs[i].dataset.floor)===idx) return cvs[i]; }
  return null;
}
function drawFloor(f,cv){
  var g=cv.getContext('2d'); g.clearRect(0,0,cv.width,cv.height);
  for(var y=0;y<f.height;y++){
    var row=f.rows[y];
    for(var x=0;x<f.width;x++){
      var c=row.charAt(x);
      g.fillStyle = c==='#' ? '#2b3440' : (c==='E' ? '#6aa9ff' : '#e8edf2');
      g.fillRect(x*CS+1,y*CS+1,CS-2,CS-2);
    }
  }
  if(building.portals){
    g.fillStyle='#0d1117'; g.font='10px monospace'; g.textAlign='center';
    building.portals.forEach(function(p){ if(p.floor===f.index) g.fillText('S'+p.shaft,p.x*CS+CS/2,p.y*CS+CS/2+3); });
  }
}
function drawOverlay(){
  if(!segments) return;
  segments.forEach(function(s){
    if(s.type==='walk'){
      var cv=floorCanvas(s.floor); if(!cv) return; var g=cv.getContext('2d');
      g.fillStyle='rgba(255,140,0,0.28)';
      s.path.forEach(function(p){ g.fillRect(p[0]*CS+1,p[1]*CS+1,CS-2,CS-2); });
      g.strokeStyle='#ff8c00'; g.lineWidth=3; g.lineJoin='round'; g.beginPath();
      s.path.forEach(function(p,i){ var cx=p[0]*CS+CS/2, cy=p[1]*CS+CS/2; if(i===0) g.moveTo(cx,cy); else g.lineTo(cx,cy); });
      g.stroke();
    } else {
      var arrow = s.toFloor>s.fromFloor ? '^' : 'v';
      [s.fromFloor,s.toFloor].forEach(function(fl){
        var cv=floorCanvas(fl); if(!cv) return; var g=cv.getContext('2d');
        var cx=s.x*CS+CS/2, cy=s.y*CS+CS/2;
        g.fillStyle='#b07cff'; g.beginPath(); g.arc(cx,cy,CS*0.34,0,7); g.fill();
        g.fillStyle='#0d1117'; g.font='9px monospace'; g.textAlign='center';
        g.fillText(s.fromFloor+arrow+s.toFloor,cx,cy+3);
      });
    }
  });
  drawMarker(val('sf'),val('sx'),val('sy'),'#2ecc71');
  drawMarker(val('tf'),val('tx'),val('ty'),'#e5484d');
}
function drawMarker(f,x,y,color){
  if(f===null||x===null||y===null) return;
  var cv=floorCanvas(f); if(!cv) return; var g=cv.getContext('2d');
  var cx=x*CS+CS/2, cy=y*CS+CS/2;
  g.fillStyle=color; g.beginPath(); g.arc(cx,cy,CS*0.30,0,7); g.fill();
  g.strokeStyle='#0d1117'; g.lineWidth=2; g.stroke();
}
function redraw(){ building.floors.forEach(function(f){ drawFloor(f,floorCanvas(f.index)); }); drawOverlay(); }
function onCellClick(ev,f){
  var rect=ev.target.getBoundingClientRect();
  var x=Math.floor((ev.clientX-rect.left)/CS), y=Math.floor((ev.clientY-rect.top)/CS);
  if(x<0||y<0||x>=f.width||y>=f.height) return;
  if(!clickTarget){ el('sf').value=f.index; el('sx').value=x; el('sy').value=y; el('status').textContent='Start = floor '+f.index+' ('+x+','+y+'). Click/enter target next.'; }
  else { el('tf').value=f.index; el('tx').value=x; el('ty').value=y; el('status').textContent='Target = floor '+f.index+' ('+x+','+y+'). Press Plan.'; }
  clickTarget=!clickTarget; redraw();
}
function plan(){
  var sf=val('sf'),sx=val('sx'),sy=val('sy'),tf=val('tf'),tx=val('tx'),ty=val('ty');
  if([sf,sx,sy,tf,tx,ty].some(function(v){return v===null;})){ el('status').textContent='Enter all six start/target coordinates.'; return; }
  var qs='/plan?sf='+sf+'&sx='+sx+'&sy='+sy+'&tf='+tf+'&tx='+tx+'&ty='+ty;
  fetch(qs).then(function(r){return r.json();}).then(function(res){
    if(!res.ok){ segments=null; redraw(); el('status').textContent='No route found between those cells.'; return; }
    segments=res.segments;
    el('status').textContent='Route: '+res.segments.length+' segments, '+res.walkCells+' walk cells, '+res.rides+' ride(s).';
    redraw();
  });
}
function clearRoute(){ segments=null; ['sf','sx','sy','tf','tx','ty'].forEach(function(id){el(id).value='';}); clickTarget=false; el('status').textContent='Cleared.'; redraw(); }
</script>
</body>
</html>
""".trimIndent()
