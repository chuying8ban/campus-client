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

/**
 * 学籍页（`/student/for-std/student-info`）就地解析：教务这页是服务端渲染的 HTML，
 * **没有 JSON 接口**（2026-10-18 实测；`/student/info` 之类一律 404）。
 *
 * 解析办法不依赖标签名/class：把整页**文本节点按文档顺序**串成一列，教务的排版永远是
 * 「标签 值 标签 值」，所以"标签后面紧跟的那一格"就是值。这样换皮肤、换 DOM 结构都不至于全废。
 *
 * 结果放 `window.__eamsProfile`（JSON 字符串）；失败放 `window.__eamsProfileError`。
 * 只取对日常有用的六项 —— 考生号、身份证号、银行卡号这些故意不取。
 */
val profileScript = """
    (function(){
      window.__eamsProfile=null; window.__eamsProfileError=null;
      var LABELS=['学号','培养类型','管理部门','年级','学历层次','学生类别','专业院系','专业','专业方向','行政班','培养方案','学制','学籍状态','是否在籍','是否在校','校区','辅导员'];
      function pick(seq,label){
        for(var i=0;i<seq.length;i++){
          if(seq[i]!==label) continue;
          for(var j=i+1;j<Math.min(i+4,seq.length);j++){
            var v=seq[j];
            if(LABELS.indexOf(v)>=0) break;
            v=v.replace(/\s*\([^)]*\)\s*${'$'}/,'').replace(/\s*（[^）]*）\s*${'$'}/,'').trim();
            if(v && v!=='--' && v!=='—') return v;
          }
        }
        return null;
      }
      try{
        fetch('${'$'}{PROFILE_PATH}',{credentials:'same-origin',headers:{'Accept':'text/html'}})
          .then(function(r){ if(!r.ok) throw new Error('HTTP '+r.status); return r.text(); })
          .then(function(t){
            var doc=new DOMParser().parseFromString(t,'text/html');
            var body=doc.body||doc.documentElement;
            var walker=doc.createTreeWalker(body,NodeFilter.SHOW_TEXT,null,false);
            var seq=[],n;
            while((n=walker.nextNode())){
              var s=(n.nodeValue||'').replace(/\s+/g,' ').trim();
              if(s) seq.push(s);
            }
            window.__eamsProfile=JSON.stringify({
              studentId:pick(seq,'学号'),
              college:pick(seq,'管理部门')||pick(seq,'专业院系'),
              major:pick(seq,'专业'),
              adminClass:pick(seq,'行政班'),
              counselor:pick(seq,'辅导员'),
              status:pick(seq,'学籍状态'),
              campus:pick(seq,'校区'),
              grade:pick(seq,'年级'),
              program:pick(seq,'培养方案')
            });
          })
          .catch(function(e){ window.__eamsProfileError=String((e&&e.message)||e); });
      }catch(e){ window.__eamsProfileError=String(e); }
    })()
""".trimIndent().replace("\${PROFILE_PATH}", "/student/for-std/student-info")

/**
 * 考试 + 成绩的 harvest：把两页里**能拿的都抠出来**，不做归类。
 *
 * 为什么要"抠整页"而不是认死一个变量名：这两页的写法随时会变 ——
 * 考试页是内联 `var studentExamList`，成绩页是页面脚本渲染出来的表格。
 * 于是这里同时交两样东西给 Kotlin：
 *   - `arrays`：页面里所有 `var X = [...]` 的内联数组（有名有行）；
 *   - `tables`：页面上真实渲染出来的 `table`（表头 + 单元格文字）。
 * 归类（哪个是"考试时间"、哪个是"绩点"）放在 [parseRecords]，因为那边能写单测；
 * 这段 JS 只负责搬运，认不出来也绝不猜。
 */
val recordsScript = """
    (function(){
      window.__eamsRecords=null; window.__eamsRecordsError=null;
      var PAGES={exams:'/student/for-std/exam-arrange',grades:'/student/for-std/grade/sheet'};
      function clean(s){ return (s||'').replace(/\s+/g,' ').trim(); }
      function harvest(html){
        var doc=new DOMParser().parseFromString(html,'text/html');
        var arrays=[],tables=[];
        var re=/var\s+([A-Za-z_${'$'}][\w${'$'}]*)\s*=\s*(\[[\s\S]*?\]);/g,m;
        while((m=re.exec(html))){
          try{
            var arr=JSON.parse(m[2]);
            if(arr&&arr.length&&typeof arr[0]==='object') arrays.push({name:m[1],rows:arr.slice(0,300)});
          }catch(e){}
        }
        var ts=doc.querySelectorAll('table');
        for(var i=0;i<ts.length;i++){
          var t=ts[i],heads=[],rows=[];
          var hs=t.querySelectorAll('thead th');
          if(!hs.length) hs=t.querySelectorAll('tr:first-child th, tr:first-child td');
          for(var j=0;j<hs.length;j++) heads.push(clean(hs[j].textContent));
          var trs=t.querySelectorAll('tbody tr');
          if(!trs.length) trs=t.querySelectorAll('tr');
          for(var k=0;k<trs.length;k++){
            var tds=trs[k].querySelectorAll('td');
            if(tds.length<2) continue;
            var cells=[];
            for(var q=0;q<tds.length;q++) cells.push(clean(tds[q].textContent));
            rows.push(cells);
          }
          if(heads.length&&rows.length) tables.push({headers:heads,rows:rows.slice(0,300)});
        }
        return {arrays:arrays,tables:tables};
      }
      var keys=Object.keys(PAGES), out={}, left=keys.length;
      keys.forEach(function(k){
        fetch(PAGES[k],{credentials:'same-origin',headers:{'Accept':'text/html'}})
          .then(function(r){ if(!r.ok) throw new Error('HTTP '+r.status); return r.text(); })
          .then(function(t){ out[k]=harvest(t); })
          .catch(function(e){ out[k]={arrays:[],tables:[],error:String((e&&e.message)||e)}; })
          .then(function(){ if(--left===0) window.__eamsRecords=JSON.stringify(out); });
      });
    })()
""".trimIndent()

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
