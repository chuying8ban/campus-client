package top.ccbase.campus.school

/** Tree-view print-data protocol only; not a universal school API. */
object ShuweiExtraction {
fun fetchScript(path: String): String {
    require(path.startsWith("/student/") && !path.contains("..") && path.none { it == '\'' || it == '"' || it == '\\' || it.isISOControl() }) { "非法课表请求路径" }
    return """
    (function(){
      window.__eamsPayload=null; window.__eamsError=null;
      try{
        fetch('$path',{credentials:'same-origin',headers:{'Accept':'application/json'}})
          .then(function(r){
            return r.text().then(function(t){
              if(!r.ok){ window.__eamsError='HTTP '+r.status; return; }
              var ct=(r.headers&&r.headers.get)?(r.headers.get('content-type')||''):'';
              if(ct.indexOf('json')<0 && t.charAt(0)!=='{'){ window.__eamsError='会话失效：返回的不是课表 JSON'; return; }
              window.__eamsPayload=t;
            });
          })
          .catch(function(e){ window.__eamsError=String(e); });
      }catch(e){ window.__eamsError=String(e); }
    })()
""".trimIndent()
}

/** 学期 id 提取：对齐 [EamsClient.currentSemesterId] 的口径（用 currentSemester 的 abbrEn 匹配 semesters）。 */
val semesterScript = """
    (function(){
      try{
        function pickId(list, abbr){
          if(typeof list==='string'){ try{ list=JSON.parse(list); }catch(e){ return null; } }
          if(!Array.isArray(list)||list.length===0) return null;
          var p=null;
          if(abbr){ for(var i=0;i<list.length;i++){ var x=list[i]; if(x&&(x.abbrEn===abbr||x.abbr===abbr)){ p=x; break; } } }
          if(!p) p=list[0];
          return p&&(p.id!==undefined?p.id:p.semesterId);
        }
        var sem=window.semesters, cur=window.currentSemester;
        if(!sem||!cur){
          var html=document.documentElement?document.documentElement.innerHTML:'';
          var m=html.match(/var\s+semesters\s*=\s*JSON\.parse\(\s*'([\s\S]*?)'\s*\)/);
          if(!sem&&m){ try{ sem=JSON.parse(m[1]); }catch(e){} }
          if(!cur){ var m2=html.match(/var\s+currentSemester\s*=\s*([\s\S]*?);/); if(m2){ try{ cur=JSON.parse(m2[1]); }catch(e){} } }
        }
        var abbr=(cur&&(cur.abbrEn||cur.abbr||cur.name))||null;
        var id=pickId(sem,abbr);
        if(id===null||id===undefined||isNaN(Number(id))) return '__EAMS_ERROR__ 找不到学期（先在官方页面登录）';
        return String(id);
      }catch(e){ return '__EAMS_ERROR__ '+e; }
    })()
""".trimIndent()


}
