(function(){const a=document.createElement("link").relList;if(a&&a.supports&&a.supports("modulepreload"))return;for(const o of document.querySelectorAll('link[rel="modulepreload"]'))r(o);new MutationObserver(o=>{for(const n of o)if(n.type==="childList")for(const i of n.addedNodes)i.tagName==="LINK"&&i.rel==="modulepreload"&&r(i)}).observe(document,{childList:!0,subtree:!0});function t(o){const n={};return o.integrity&&(n.integrity=o.integrity),o.referrerPolicy&&(n.referrerPolicy=o.referrerPolicy),o.crossOrigin==="use-credentials"?n.credentials="include":o.crossOrigin==="anonymous"?n.credentials="omit":n.credentials="same-origin",n}function r(o){if(o.ep)return;o.ep=!0;const n=t(o);fetch(o.href,n)}})();const U=20;class _ extends Error{status;detail;constructor(a,t,r=null){super(a),this.name="ApiError",this.status=t,this.detail=r}}function M(e,a){return`${String(e).replace(/\/+$/,"")}${a}`}async function y(e,a,t={}){const r=await fetch(M(e,a),{method:t.method||"GET",headers:{...t.body?{"Content-Type":"application/json"}:{},...t.headers||{}},body:t.body?JSON.stringify(t.body):void 0}),o=await r.json().catch(()=>({}));if(!r.ok)throw new _(o.erro||o.message||`HTTP ${r.status}`,r.status,o.detalhe||null);return o}function G(){return{health:"unknown",painel:"unknown",eventos:"unknown",mapa:"unknown"}}async function V(e){const a=G(),t=[],[r,o,n,i]=await Promise.allSettled([y(e,"/health"),y(e,"/api/operacao/painel"),y(e,`/api/operacao/eventos?limite=${U}`),y(e,"/api/operacao/mapa")]),l=E(r,"health",a,t),p=E(o,"painel",a,t),d=E(n,"eventos",a,t),u=E(i,"mapa",a,t);if(!l&&!p&&!d&&!u)throw new Error("Nao foi possivel carregar nenhum read model operacional.");return{health:l,painel:p,eventos:d,mapa:u,readiness:a,partialErrors:t,fetchedAt:new Date().toISOString()}}function E(e,a,t,r){return e.status==="fulfilled"?(t[a]="ok",e.value):(t[a]="error",r.push(`${a}: ${e.reason instanceof Error?e.reason.message:"falha"}`),null)}function J(e){return e instanceof Error?e.message:"Falha ao sincronizar a operacao."}function K(e){const{root:a,router:t,store:r,persistApiBase:o,persistAutoRefresh:n}=e,i=async()=>{if(r.getState().sync.status!=="loading"){r.startSync();try{const d=await V(r.getState().connection.apiBase);r.finishSync(d)}catch(d){r.failSync(J(d))}}},l=d=>{const m=(d.target instanceof HTMLElement?d.target:null)?.closest("[data-action]");if(!m||!a.contains(m))return;const h=m.dataset.action;if(h==="save-api-base"){r.commitConnection(),o(r.getState().connection.apiBase),i();return}if(h==="refresh-snapshot"){i();return}if(h==="navigate"){const g=m.dataset.moduleId;if(!g)return;t.navigate(g),r.setActiveModule(g)}},p=d=>{const u=d.target;if(!(!(u instanceof HTMLInputElement)||!a.contains(u))){if(u.id==="api-base-input"){r.setConnectionDraft(u.value);return}if(u.id==="auto-refresh-input"){const m=!!u.checked;r.setAutoRefresh(m),n(m)}}};return{bind(){a.addEventListener("click",l),a.addEventListener("input",p)},refreshSnapshot:i,dispose(){a.removeEventListener("click",l),a.removeEventListener("input",p),t.dispose()}}}const k=[{id:"cockpit",hash:"#/cockpit",label:"Cockpit",description:"Visao consolidada da operacao, dos alertas e dos read models.",status:"active"},{id:"atendimento",hash:"#/atendimento",label:"Atendimento",description:"Busca de cliente na sessao, criacao segura de pedido e handoff conectado ao despacho.",status:"planned"},{id:"despacho",hash:"#/despacho",label:"Despacho",description:"Cockpit principal para fila operacional, camadas de frota, risco e acoes de saida.",status:"planned"},{id:"entregador",hash:"#/entregador",label:"Entregador",description:"Base reservada para roteiro, progresso de rota, deep link e eventos terminais.",status:"planned"}];function A(e){const a=k.find(t=>t.id===e);if(!a)throw new Error(`Modulo desconhecido: ${e}`);return a}function T(e){return A(e).hash}function Q(e){return k.some(a=>a.id===e)}function R(e){const a=String(e||"").replace(/^#\/?/,"").trim().toLowerCase();return Q(a)?a:"cockpit"}function Y(e){const a=e.targetWindow??window;let t=null;const r=()=>{t!==null&&(a.clearInterval(t),t=null)},o=()=>{r(),t=a.setInterval(e.onTick,e.intervalMs)};return{sync(n){if(!n){r();return}o()},stop(){r()}}}function X(e,a=window){let t=R(a.location.hash);const r=()=>{const o=R(a.location.hash);o!==t&&(t=o,e(o))};return{getCurrentModule(){return t},navigate(o){t=o;const n=T(o);a.location.hash!==n&&(a.location.hash=n)},start(){a.addEventListener("hashchange",r);const o=T(t);a.location.hash!==o&&(a.location.hash=o)},dispose(){a.removeEventListener("hashchange",r)}}}function Z(e){let a=e;const t=new Set,r=()=>{t.forEach(n=>n(a))},o=(n,i={})=>{a=n(a),i.notify!==!1&&r()};return{getState(){return a},subscribe(n){return t.add(n),()=>{t.delete(n)}},setConnectionDraft(n){o(i=>({...i,connection:{...i.connection,apiBaseDraft:n}}),{notify:!1})},commitConnection(){o(n=>{const i=n.connection.apiBaseDraft.trim()||n.connection.apiBase;return{...n,connection:{...n.connection,apiBase:i,apiBaseDraft:i}}})},setAutoRefresh(n){o(i=>({...i,connection:{...i.connection,autoRefresh:n}}))},setActiveModule(n){o(i=>({...i,activeModule:n}))},updateAtendimento(n,i){o(l=>({...l,atendimento:n(l.atendimento)}),i)},setEntregadorId(n){o(i=>({...i,entregador:{...i.entregador,entregadorId:n,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}}))},startSync(){o(n=>({...n,sync:{...n.sync,status:"loading",lastError:null}}))},finishSync(n){o(i=>({...i,snapshot:n,sync:{status:"ready",lastError:null}}))},failSync(n){o(i=>({...i,sync:{status:"error",lastError:n}}))},startDespachoRouteStart(){o(n=>({...n,despacho:{...n.despacho,routeStart:{status:"loading",lastError:null},lastRouteStart:null}}))},finishDespachoRouteStart(n){o(i=>({...i,despacho:{routeStart:{status:"ready",lastError:null},lastRouteStart:{tone:n.idempotente?"warn":"ok",title:n.idempotente?"Acao reconhecida como idempotente":"Rota pronta iniciada",detail:`R${n.rotaId} disparada para o pedido ${n.pedidoId} e entrega ${n.entregaId}.`,payload:n}}}))},failDespachoRouteStart(n){o(i=>({...i,despacho:{routeStart:{status:"error",lastError:n},lastRouteStart:{tone:"danger",title:"Falha ao iniciar rota pronta",detail:n,payload:null}}}))},startEntregadorSync(){o(n=>({...n,entregador:{...n.entregador,sync:{status:"loading",lastError:null}}}))},finishEntregadorSync(n,i){o(l=>({...l,entregador:{...l.entregador,roteiro:n,fetchedAt:i,sync:{status:"ready",lastError:null}}}))},failEntregadorSync(n){o(i=>({...i,entregador:{...i.entregador,sync:{status:"error",lastError:n}}}))},startEntregadorAction(){o(n=>({...n,entregador:{...n.entregador,action:{status:"loading",lastError:null}}}))},finishEntregadorAction(n){o(i=>({...i,entregador:{...i.entregador,action:{status:"ready",lastError:null},lastAction:n}}))},failEntregadorAction(n){o(i=>({...i,entregador:{...i.entregador,action:{status:"error",lastError:n},lastAction:{tone:"danger",title:"Acao rejeitada",detail:n,payload:null}}}))}}}function s(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function c(e,a){return`<span class="pill ${a}">${s(e)}</span>`}function W(e){return`
    <article class="signal-card tone-${e.tone}">
      <p class="panel-kicker">${s(e.label)}</p>
      <strong>${s(e.value)}</strong>
      <p>${s(e.detail)}</p>
    </article>
  `}function ee(e){return`
    <article class="metric-card tone-${e.tone}">
      <p class="metric-label">${s(e.label)}</p>
      <p class="metric-value">${s(e.value)}</p>
      <p class="metric-detail">${s(e.detail)}</p>
    </article>
  `}function ae(e){return`
    <article class="priority-card tone-${e.tone}">
      <div class="priority-topline">
        ${c(e.badge,e.tone)}
        <strong>${s(e.title)}</strong>
      </div>
      <p>${s(e.detail)}</p>
      <p class="priority-action">${s(e.action)}</p>
    </article>
  `}function L(e){return`
    <article class="route-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.detail)}</p>
        </div>
        ${c(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="route-stat-row mono">
        ${e.meta.map(a=>`<span>${s(a)}</span>`).join("")}
      </div>
    </article>
  `}function te(e){return`
    <article class="queue-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.summary)}</p>
        </div>
        ${c(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="queue-card-stats mono">
        ${e.lines.map(a=>`<span>${s(a)}</span>`).join("")}
      </div>
    </article>
  `}function ne(e){return`
    <section class="queue-lane tone-${e.tone}">
      <div class="queue-lane-header">
        <div>
          <p class="panel-kicker">Etapa ${s(e.step)}</p>
          <h3>${s(e.title)}</h3>
          <p class="queue-lane-copy">${s(e.summary)}</p>
        </div>
        ${c(`${e.count} item(ns)`,e.tone)}
      </div>
      <div class="queue-list">
        ${e.cards.length?e.cards.map(te).join(""):`<p class="empty-copy">${s(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function oe(e){return`
    <article class="readiness-item tone-${e.tone}">
      <p class="panel-kicker">${s(e.label)}</p>
      <strong>${s(e.title)}</strong>
      <p>${s(e.detail)}</p>
    </article>
  `}function re(e){return`
    <article class="event-item tone-${e.tone}">
      <div class="event-topline">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.subject)}</p>
        </div>
        ${c(e.badgeLabel,e.badgeTone)}
      </div>
      <p>${s(e.detail)}</p>
      <p class="event-meta mono">${s(e.meta)}</p>
    </article>
  `}function se(e){return`
    <article class="micro-card tone-${e.tone}">
      <p class="panel-kicker">${s(e.label)}</p>
      <strong>${s(e.value)}</strong>
      <p>${s(e.detail)}</p>
    </article>
  `}function ie(e){return`
    <article class="route-card large tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.summary)}</p>
        </div>
        ${c(e.badgeLabel,e.badgeTone)}
      </div>
      <p class="mono">${s(e.detail)}</p>
      <div class="tag-row">
        ${e.tags.map(a=>c(a,"muted")).join("")}
      </div>
    </article>
  `}function ce(e){return`
    <section class="panel cockpit-overview">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Cockpit operacional</p>
          <h2>${s(e.headline)}</h2>
          <p class="section-copy">${s(e.executiveSummary)}</p>
        </div>
        ${c(e.modeLabel,e.modeTone)}
      </div>
      <div class="hero-signals">
        ${e.signals.map(W).join("")}
      </div>
    </section>

    <section class="metrics-grid">
      ${e.metrics.map(ee).join("")}
    </section>

    <section class="panel priority-panel">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Direcionamento imediato</p>
          <h2>O que merece atencao primeiro</h2>
          <p class="section-copy">${s(e.nextActionDetail)}</p>
        </div>
        ${c(e.nextAction,e.nextActionTone)}
      </div>
      <div class="priority-layout">
        <article class="priority-lead tone-${e.leadAlert.tone}">
          <div class="priority-topline">
            ${c("foco da rodada",e.leadAlert.tone)}
            <strong>${s(e.leadAlert.title)}</strong>
          </div>
          <p>${s(e.leadAlert.detail)}</p>
          <p class="priority-action">${s(e.leadAlert.action)}</p>
        </article>
        <div class="priority-support">
          ${e.supportingAlerts.length?e.supportingAlerts.map(ae).join(""):`
                <article class="priority-card tone-ok">
                  <div class="priority-topline">
                    ${c("estavel","ok")}
                    <strong>Sem pressao adicional</strong>
                  </div>
                  <p>Os indicadores desta rodada nao mostraram um segundo foco concorrendo com a prioridade principal.</p>
                  <p class="priority-action">Mantenha o monitoramento normal enquanto a operacao seguir limpa.</p>
                </article>
              `}
        </div>
      </div>
      <div class="pulse-grid">
        ${e.pulses.map(a=>`
              <article class="pulse-card">
                <p class="panel-kicker">${s(a.label)}</p>
                <strong>${s(a.value)}</strong>
              </article>
            `).join("")}
      </div>
    </section>

    <main class="content-grid">
      <div class="content-main">
        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Fluxo operacional</p>
              <h2>Triagem por etapa</h2>
              <p class="section-copy">${s(e.nextActionDetail)}</p>
            </div>
            ${c(e.panelUpdatedAt,"muted")}
          </div>
          <div class="routes-board">
            <article class="route-group tone-${e.activeRoutes.length>0?"info":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas em campo</p>
                  <strong class="route-group-count">${s(String(e.activeRoutes.length))}</strong>
                </div>
                ${c(e.activeRoutes.length>0?`${e.activeRoutes.length} rota(s)`:"sem rota",e.activeRoutes.length>0?"info":"ok")}
              </div>
              <p class="section-copy">${s(e.activeRoutes.length>0?"Entregas rodando e pedindo acompanhamento de execucao.":"Nenhuma rota ativa na leitura atual.")}</p>
              <div class="route-list">
                ${e.activeRoutes.length?e.activeRoutes.map(L).join(""):'<p class="empty-copy">Sem rota ativa.</p>'}
              </div>
            </article>
            <article class="route-group tone-${e.plannedRoutes.length>0?"warn":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas prontas</p>
                  <strong class="route-group-count">${s(String(e.plannedRoutes.length))}</strong>
                </div>
                ${c(e.plannedRoutes.length>0?`${e.plannedRoutes.length} rota(s)`:"sem rota",e.plannedRoutes.length>0?"warn":"ok")}
              </div>
              <p class="section-copy">${s(e.plannedRoutes.length>0?"Carga ja comprometida aguardando decisao para ganhar rua.":"Nenhuma rota pronta aguardando liberacao.")}</p>
              <div class="route-list">
                ${e.plannedRoutes.length?e.plannedRoutes.map(L).join(""):'<p class="empty-copy">Sem rota planejada.</p>'}
              </div>
            </article>
          </div>
          <div class="queue-grid">
            ${e.queueLanes.map(ne).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Mapa operacional</p>
              <h2>Camadas e rotas</h2>
              <p class="section-copy">${s(e.pulses.find(a=>a.label==="Rotas")?.value||"Sem resumo de rotas.")}</p>
            </div>
            ${e.mapDeposit?c(e.mapDeposit,"muted"):""}
          </div>
          <div class="micro-grid">
            ${e.mapSummaryCards.map(se).join("")}
          </div>
          <div class="route-list">
            ${e.mapRoutes.length?e.mapRoutes.map(ie).join(""):'<p class="empty-copy">Sem rotas mapeadas.</p>'}
          </div>
        </section>
      </div>

      <aside class="content-side">
        <section class="panel readiness-panel">
          <div class="readiness-summary">
            <div>
              <p class="panel-kicker">Confianca da leitura</p>
              <h2>${s(e.confidenceLabel)}</h2>
              <p class="section-copy">${s(e.confidenceDetail)}</p>
            </div>
            <div class="readiness-meta">
              ${c(e.readinessStatus.title,e.readinessStatus.tone)}
            </div>
          </div>
          <div class="readiness-grid">
            ${e.readinessItems.map(oe).join("")}
          </div>
          ${e.notices.map(a=>`
                <div class="notice notice-${a.tone}">
                  <strong>${s(a.label)}:</strong> ${s(a.body)}
                </div>
              `).join("")}
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Ocorrencias recentes</p>
              <h2>Feed de excecoes e movimentos</h2>
              <p class="section-copy">Use este bloco para validar falhas, cancelamentos e eventos de rota antes da proxima decisao.</p>
            </div>
            ${c(e.eventBadgeLabel,e.eventBadgeTone)}
          </div>
          <div class="event-list">
            ${e.events.length?e.events.map(re).join(""):'<p class="empty-copy">Nenhum evento retornado.</p>'}
          </div>
        </section>
      </aside>
    </main>
  `}function le(e){if(!e||!e.painel)return{alerts:[{tone:"warn",title:"Sem leitura operacional suficiente",detail:"A interface ainda nao conseguiu carregar o painel principal.",action:"Validar API base e atualizar a sincronizacao."}],headline:"Aguardando dados operacionais",queueSummary:"Fila indisponivel",routeSummary:"Rotas indisponiveis",eventSummary:"Eventos indisponiveis",executiveSummary:"A operacao ainda nao entregou leitura suficiente para orientar uma decisao com seguranca.",modeLabel:"Aguardando leitura",modeTone:"warn",nextAction:"Validar conexao",nextActionDetail:"Conferir API base e executar nova sincronizacao antes de operar.",nextActionTone:"warn",confidenceLabel:"Baixa confianca",confidenceDetail:"Sem painel principal, qualquer decisao pode estar desatualizada.",confidenceTone:"danger"};const a=e.painel,t=e.mapa,r=e.eventos?.eventos||[],o=[];(e.health?.status!=="ok"||e.health?.database!=="ok")&&o.push({tone:"danger",title:"Infra com degradacao",detail:"API ou banco nao responderam como esperado nesta leitura.",action:"Conferir health e estabilizar a base antes de operar manualmente."}),e.partialErrors.length>0&&o.push({tone:"warn",title:"Visao parcial da operacao",detail:e.partialErrors.join(" | "),action:"Atualizar novamente antes de tomar decisao de despacho."});const n=a.filas.pendentesElegiveis.filter(z=>z.janelaTipo==="HARD").length;n>0&&o.push({tone:"danger",title:`${n} pedido(s) HARD aguardando despacho`,detail:"Esses pedidos merecem prioridade maxima porque a janela e mais restrita.",action:"Separar esses pedidos primeiro na avaliacao de fila."});const i=a.rotas.planejadas.length;i>0&&o.push({tone:"warn",title:`${i} rota(s) pronta(s) para iniciar`,detail:"Existe carga ja comprometida aguardando acao operacional.",action:"Confirmar se a capacidade da frota permite iniciar a proxima rota."});const l=P(r);l>0&&o.push({tone:"info",title:`${l} ocorrencia(s) recente(s) de falha/cancelamento`,detail:"O feed operacional registrou eventos de excecao nas ultimas leituras.",action:"Olhar o bloco de ocorrencias para entender o contexto antes da proxima acao."});const p=ge(t);p.consistent||o.push({tone:"warn",title:"Camadas da frota fora do esperado",detail:p.message,action:"Revisar leitura de rotas antes de assumir que existe uma unica primaria e uma unica secundaria."}),o.length===0&&o.push({tone:"ok",title:"Operacao em regime estavel",detail:"Nao surgiram sinais imediatos de excecao na leitura atual.",action:"Seguir monitorando fila, eventos e rotas por cadencia normal."});const d=de(a),u=ue(a),m=pe(a,t,p.message),h=me(r),g=o[0];return{alerts:o.slice(0,4),headline:d,queueSummary:u,routeSummary:m,eventSummary:h,executiveSummary:`${d}. ${u} ${m}`,modeLabel:g?.title||"Operacao estavel",modeTone:g?.tone||"ok",nextAction:g?.action||"Seguir monitorando",nextActionDetail:g?.detail||"Sem excecoes imediatas na leitura atual.",nextActionTone:g?.tone||"ok",confidenceLabel:e.partialErrors.length>0?"Confianca moderada":e.health?.status==="ok"?"Confianca alta":"Confianca reduzida",confidenceDetail:e.partialErrors.length>0?"Ha leituras parciais nesta sincronizacao; valide antes de decidir.":e.health?.status==="ok"?"Painel, eventos e mapa responderam como esperado.":"Infra ou banco sinalizaram degradacao.",confidenceTone:e.partialErrors.length>0?"warn":e.health?.status==="ok"?"ok":"danger"}}function P(e){return e.filter(a=>{const t=String(a.eventType||"").toUpperCase();return t.includes("FALHOU")||t.includes("CANCELADO")}).length}function de(e){return e.filas.pendentesElegiveis.length>0?"Fila exigindo triagem ativa":e.rotas.emAndamento.length>0?"Entrega em curso com operacao ja movimentada":e.rotas.planejadas.length>0?"Operacao pronta para iniciar nova rota":"Base limpa para acompanhamento"}function ue(e){return`${e.pedidosPorStatus.pendente} pendente(s), ${e.pedidosPorStatus.confirmado} confirmado(s), ${e.pedidosPorStatus.emRota} em rota.`}function pe(e,a,t){const r=a?.rotas.filter(n=>n.camada==="PRIMARIA").length??0,o=a?.rotas.filter(n=>n.camada==="SECUNDARIA").length??0;return`${e.rotas.emAndamento.length} em andamento, ${e.rotas.planejadas.length} planejada(s), ${r} primaria(s), ${o} secundaria(s). ${t}`}function me(e){if(e.length===0)return"Sem ocorrencias recentes.";const a=P(e);return a>0?`${a} excecao(oes) recente(s) em ${e.length} evento(s) lido(s).`:`${e.length} evento(s) recente(s) sem excecao imediata.`}function ge(e){if(!e||e.rotas.length===0)return{consistent:!0,message:"Sem rotas mapeadas."};const a=e.rotas.filter(o=>o.camada==="PRIMARIA").length,t=e.rotas.filter(o=>o.camada==="SECUNDARIA").length;return a<=1&&t<=1?{consistent:!0,message:"Camadas dentro da expectativa atual."}:{consistent:!1,message:`Leitura retornou ${a} primaria(s) e ${t} secundaria(s).`}}function f(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function fe(e){return typeof e!="number"||Number.isNaN(e)?"-":`${e.toFixed(1)}%`}const he=8;function S(e){if(!e)return"-";const a=new Date(e);if(Number.isNaN(a.getTime()))return e;const t=Date.now()-a.getTime(),r=Math.round(Math.abs(t)/6e4);if(r<1)return"agora";if(r<60)return t>=0?`${r} min atras`:`em ${r} min`;const o=Math.round(r/60);if(o<24)return t>=0?`${o} h atras`:`em ${o} h`;const n=Math.round(o/24);return t>=0?`${n} dia(s) atras`:`em ${n} dia(s)`}function v(e){return e?e.toLowerCase().split(/[_-]+/).filter(Boolean).map(a=>a.charAt(0).toUpperCase()+a.slice(1)).join(" "):"-"}function ve(e){return e==="health"?"Health":e==="painel"?"Painel":e==="eventos"?"Eventos":"Mapa"}function $e(e){return e==="ok"?"ok":e==="error"?"danger":"muted"}function be(e){return e==="danger"?"agir agora":e==="warn"?"atencao":e==="info"?"acompanhar":"estavel"}function D(e){const a=String(e.eventType||"").toUpperCase(),t=String(e.status||"").toUpperCase();return a.includes("FALHOU")||a.includes("CANCELADO")||t.includes("ERRO")||t.includes("FALHA")?"danger":a.includes("ENTREGUE")?"ok":a.includes("ROTA")?"info":"warn"}function ye(e){return e==="PRIMARIA"?"info":"warn"}function j(e){return(e.snapshot?.eventos?.eventos||[]).filter(t=>D(t)==="danger").length}function Ee(e){const a=e.snapshot?.painel,t=a?.filas.pendentesElegiveis.filter(d=>d.janelaTipo==="HARD").length??0,r=a?.rotas.emAndamento.length??0,o=a?.rotas.planejadas.length??0,n=j(e),i=a?.indicadoresEntrega.taxaSucessoPercentual,l=a?.indicadoresEntrega.totalFinalizadas,p=a?.indicadoresEntrega.entregasConcluidas;return[{label:"Fila para triar",value:a?String(a.filas.pendentesElegiveis.length):"-",detail:a?`${a.pedidosPorStatus.pendente} pedido(s) pendente(s) na visao geral`:"aguardando leitura do painel",tone:a?t>0?"danger":a.filas.pendentesElegiveis.length>0?"warn":"ok":"muted"},{label:"Pedidos HARD",value:a?String(t):"-",detail:t>0?"janela critica exigindo prioridade maxima":"sem pedido critico escondido na fila",tone:a?t>0?"danger":"ok":"muted"},{label:"Rotas prontas",value:a?String(o):"-",detail:o>0?"aguardando decisao de liberar saida":"sem rota pronta esperando liberacao",tone:a?o>0?"warn":"ok":"muted"},{label:"Rotas em campo",value:a?String(r):"-",detail:r>0?"execucao ativa pedindo acompanhamento":"sem entrega em curso nesta rodada",tone:a?r>0?"info":"ok":"muted"},{label:"Excecoes recentes",value:e.snapshot?String(n):"-",detail:n>0?"falhas ou cancelamentos visiveis no feed":"feed recente sem falha aparente",tone:e.snapshot?n>=3?"danger":n>0?"warn":"ok":"muted"},{label:"Taxa de sucesso",value:a?fe(i):"-",detail:a&&typeof l=="number"&&typeof p=="number"?`${p} concluida(s) em ${l} finalizada(s)`:"indicador ainda indisponivel",tone:!a||typeof i!="number"?"muted":i>=95?"ok":i>=85?"warn":"danger"}]}function Ae(e){const a=e.snapshot?.readiness;return["health","painel","eventos","mapa"].map(r=>{const o=a?.[r]||"unknown";return{label:ve(r),title:o==="ok"?"Leitura pronta":o==="error"?"Falhou nesta rodada":"Aguardando resposta",detail:o==="ok"?"Fonte pronta para sustentar o cockpit.":o==="error"?"Precisa de nova tentativa ou validacao manual.":"Ainda sem retorno desta fonte.",tone:$e(o)}})}function Re(e){const a=[];return e.snapshot?.partialErrors.length&&a.push({label:"Leitura parcial",body:e.snapshot.partialErrors.join(" | "),tone:"warn"}),e.sync.lastError&&a.push({label:"Ultimo erro",body:e.sync.lastError,tone:"danger"}),a}function Se(e){return(e.snapshot?.painel?.rotas.emAndamento||[]).map(t=>({title:`R${t.rotaId}`,detail:`Entregador ${t.entregadorId}`,meta:[`${t.pendentes} pendente(s)`,`${t.emExecucao} em execucao`],badgeLabel:"em campo",badgeTone:"info",tone:"info"}))}function ke(e){return(e.snapshot?.painel?.rotas.planejadas||[]).map(t=>({title:`R${t.rotaId}`,detail:`Entregador ${t.entregadorId}`,meta:[`${t.pendentes} parada(s) pronta(s)`],badgeLabel:"aguardando saida",badgeTone:"warn",tone:"warn"}))}function xe(e){const a=e.janelaTipo==="HARD";return{title:`Pedido #${e.pedidoId}`,summary:a?"Janela critica para despacho.":"Janela flexivel em triagem.",badgeLabel:a?"prioridade maxima":"fila ativa",badgeTone:a?"danger":"warn",tone:a?"danger":"warn",lines:[`${e.quantidadeGaloes} galao(oes)`,`Criado ${S(e.criadoEm)}`,f(e.criadoEm)]}}function Ie(e){return{title:`Pedido #${e.pedidoId}`,summary:"Carga confirmada na secundaria aguardando liberacao de rota.",badgeLabel:`R${e.rotaId}`,badgeTone:"info",tone:"info",lines:[`Entregador ${e.entregadorId}`,`Ordem ${e.ordemNaRota}`,`${e.quantidadeGaloes} galao(oes)`]}}function Te(e){return{title:`Pedido #${e.pedidoId}`,summary:`Entrega em andamento na rota ${e.rotaId}.`,badgeLabel:v(e.statusEntrega),badgeTone:"ok",tone:"ok",lines:[`Entrega ${e.entregaId}`,`Entregador ${e.entregadorId}`,`${e.quantidadeGaloes} galao(oes)`]}}function Le(e){const a=e.snapshot?.painel?.filas,t=a?.pendentesElegiveis.filter(r=>r.janelaTipo==="HARD").length??0;return[{step:"1",title:"Triar pedidos novos",summary:t>0?`${t} pedido(s) HARD precisam abrir a decisao desta fila.`:(a?.pendentesElegiveis.length??0)>0?"Fila ativa aguardando alocacao e priorizacao.":"Entrada limpa nesta rodada.",tone:t>0?"danger":(a?.pendentesElegiveis.length??0)>0?"warn":"ok",count:a?.pendentesElegiveis.length??0,cards:(a?.pendentesElegiveis||[]).map(xe),emptyMessage:"Nenhum pedido pendente elegivel."},{step:"2",title:"Liberar carga preparada",summary:(a?.confirmadosSecundaria.length??0)>0?"Pedidos ja encaixados em rota secundaria aguardando o aval operacional.":"Sem carga pronta aguardando liberacao.",tone:(a?.confirmadosSecundaria.length??0)>0?"warn":"ok",count:a?.confirmadosSecundaria.length??0,cards:(a?.confirmadosSecundaria||[]).map(Ie),emptyMessage:"Nenhum pedido aguardando rota secundaria."},{step:"3",title:"Acompanhar entrega em curso",summary:(a?.emRotaPrimaria.length??0)>0?"Entregas em rua que pedem monitoramento continuo.":"Sem pedido em execucao agora.",tone:(a?.emRotaPrimaria.length??0)>0?"info":"ok",count:a?.emRotaPrimaria.length??0,cards:(a?.emRotaPrimaria||[]).map(Te),emptyMessage:"Nenhum pedido em execucao agora."}]}function Ce(e){return(e.snapshot?.eventos?.eventos||[]).slice(0,he).map(t=>{const r=D(t);return{title:v(t.eventType),badgeLabel:v(t.status),badgeTone:r,subject:`${v(t.aggregateType)} ${t.aggregateId??"-"}`,detail:r==="danger"?"Excecao recente com potencial de alterar a prioridade operacional.":"Movimento recente da operacao registrado no feed.",meta:t.processedEm?`${S(t.createdEm)} · criado ${f(t.createdEm)} · processado ${f(t.processedEm)}`:`${S(t.createdEm)} · criado ${f(t.createdEm)}`,tone:r}})}function Pe(e){const a=e.snapshot?.mapa?.rotas||[],t=a.filter(n=>n.camada==="PRIMARIA").length,r=a.filter(n=>n.camada==="SECUNDARIA").length,o=a.reduce((n,i)=>n+i.paradas.length,0);return[{label:"Primarias",value:String(t),detail:"Rotas que sustentam a execucao principal.",tone:"info"},{label:"Secundarias",value:String(r),detail:"Rotas de apoio, preparacao ou contingencia.",tone:"warn"},{label:"Paradas mapeadas",value:String(o),detail:"Total de entregas representadas nesta leitura.",tone:"ok"}]}function De(e){return(e.snapshot?.mapa?.rotas||[]).map(t=>{const r=t.paradas.filter(i=>String(i.statusEntrega).toUpperCase().includes("ENTREGUE")).length,o=Math.max(t.paradas.length-r,0),n=ye(t.camada);return{title:`R${t.rotaId} · Entregador ${t.entregadorId}`,badgeLabel:t.camada==="PRIMARIA"?"camada primaria":"camada secundaria",badgeTone:n,summary:v(t.statusRota),detail:`${t.paradas.length} parada(s) · ${t.trajeto.length} ponto(s) no trajeto · ${r} concluida(s) · ${o} aberta(s)`,tags:t.paradas.slice(0,4).map(i=>`P${i.ordemNaRota} pedido ${i.pedidoId}`),tone:n}})}function je(e){const a=le(e.snapshot),t=a.alerts.map(o=>({badge:be(o.tone),tone:o.tone,title:o.title,detail:o.detail,action:o.action})),r=j(e);return{headline:a.headline,executiveSummary:a.executiveSummary,modeLabel:a.modeLabel,modeTone:a.modeTone,nextAction:a.nextAction,nextActionDetail:a.nextActionDetail,nextActionTone:a.nextActionTone,confidenceLabel:a.confidenceLabel,confidenceDetail:a.confidenceDetail,confidenceTone:a.confidenceTone,signals:[{label:"Modo atual",value:a.modeLabel,detail:a.executiveSummary,tone:a.modeTone},{label:"Proxima decisao",value:a.nextAction,detail:a.nextActionDetail,tone:a.nextActionTone},{label:"Confianca",value:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone}],metrics:Ee(e),leadAlert:t[0],supportingAlerts:t.slice(1),pulses:[{label:"Fila",value:a.queueSummary},{label:"Rotas",value:a.routeSummary},{label:"Ocorrencias",value:a.eventSummary}],readinessStatus:{label:a.confidenceLabel,title:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone},readinessItems:Ae(e),notices:Re(e),panelUpdatedAt:f(e.snapshot?.painel?.atualizadoEm),activeRoutes:Se(e),plannedRoutes:ke(e),queueLanes:Le(e),eventBadgeLabel:r>0?`${r} excecao(oes)`:"sem excecao",eventBadgeTone:r>=3?"danger":r>0?"warn":"ok",events:Ce(e),mapDeposit:e.snapshot?.mapa?`Deposito ${e.snapshot.mapa.deposito.lat.toFixed(4)}, ${e.snapshot.mapa.deposito.lon.toFixed(4)}`:null,mapSummaryCards:Pe(e),mapRoutes:De(e)}}function Oe(e){return`
    <section class="panel module-nav">
      <div class="panel-header">
        <div>
          <h2>Fluxos da operacao</h2>
          <p class="section-copy">Cada modulo organiza a mesma operacao sob a otica do papel que esta trabalhando agora.</p>
        </div>
        ${c(A(e.activeModule).label,"info")}
      </div>
      <div class="module-tab-list">
        ${k.map(a=>`
              <button
                class="module-tab ${a.id===e.activeModule?"is-active":""}"
                type="button"
                data-action="navigate"
                data-module-id="${s(a.id)}"
              >
                <div class="module-tab-topline">
                  <strong>${s(a.label)}</strong>
                  ${c(a.status==="active"?"ativo":"planejado",a.status==="active"?"ok":"muted")}
                </div>
                <p>${s(a.description)}</p>
              </button>
            `).join("")}
      </div>
    </section>
  `}function Ne(e){const a=A(e.activeModule);return`
    <section class="panel">
      <div class="panel-header">
        <div>
          <h2>${s(a.label)}</h2>
          <p class="section-copy">${s(a.description)}</p>
        </div>
        ${c("em preparacao","warn")}
      </div>
      <div class="notice notice-warn">
        <strong>Proxima etapa:</strong> este fluxo ja esta previsto na shell modular e entra nos PRs seguintes.
      </div>
    </section>
  `}function qe(e){return e.activeModule!=="cockpit"?Ne(e):ce(je(e))}function O(e,a){const t=A(a.activeModule),r=a.snapshot?.partialErrors.length??0;e.innerHTML=`
    <div class="app-shell">
      <header class="hero">
        <div class="hero-copy-block">
          <p class="eyebrow">Agua Viva</p>
          <h1>Operacao web por papel</h1>
          <p class="hero-copy">${s(t.description)}</p>
          <div class="hero-meta">
            ${c("dados reais","info")}
            ${c(a.connection.autoRefresh?"auto-refresh ligado":"auto-refresh desligado",a.connection.autoRefresh?"ok":"muted")}
            ${c(a.snapshot?.fetchedAt?f(a.snapshot.fetchedAt):"sem sincronizacao","muted")}
          </div>
        </div>
        <div class="hero-signals">
          <article class="signal-card">
            <p class="label">Modulo ativo</p>
            <strong>${s(t.label)}</strong>
            <p>${s(t.description)}</p>
          </article>
          <article class="signal-card">
            <p class="label">Sincronizacao</p>
            <strong>${s(a.sync.status)}</strong>
            <p>${s(a.sync.lastError||"Leitura operacional sem erro nesta rodada.")}</p>
          </article>
          <article class="signal-card">
            <p class="label">Leitura parcial</p>
            <strong>${s(String(r))}</strong>
            <p>${r>0?"Existe degradacao em pelo menos um read model.":"Todos os read models responderam nesta rodada."}</p>
          </article>
        </div>
      </header>

      <section class="panel toolbar">
        <div class="toolbar-intro">
          <p class="panel-kicker">Conexao</p>
          <h2>API operacional</h2>
          <p class="section-copy">A base vale para todos os modulos e pode ser trocada sem sair da app.</p>
        </div>
        <div class="toolbar-body">
          <div class="toolbar-fields">
            <label class="field">
              <span>API base</span>
              <input id="api-base-input" type="text" value="${s(a.connection.apiBaseDraft)}" spellcheck="false" />
            </label>
            <label class="toggle">
              <input id="auto-refresh-input" type="checkbox" ${a.connection.autoRefresh?"checked":""} />
              <span>Auto-refresh</span>
            </label>
          </div>
          <div class="toolbar-actions">
            <button class="button secondary" type="button" data-action="save-api-base">Salvar base</button>
            <button class="button primary" type="button" data-action="refresh-snapshot" ${a.sync.status==="loading"?"disabled":""}>
              ${a.sync.status==="loading"?"Atualizando...":"Atualizar agora"}
            </button>
          </div>
        </div>
      </section>

      ${Oe(a)}
      ${qe(a)}
    </div>
  `}const N="agua-viva.operacao-web.api-base",q="agua-viva.operacao-web.auto-refresh",Fe="http://localhost:8082";function F(e){try{return window.localStorage.getItem(e)}catch{return null}}function B(e,a){try{window.localStorage.setItem(e,a)}catch{}}function Be(){return F(N)||Fe}function He(e){B(N,e)}function we(){return F(q)!=="0"}function ze(e){B(q,e?"1":"0")}const Ue=15e3,_e=1,H=document.querySelector("#app");if(!H)throw new Error("Elemento #app nao encontrado.");const x=H,C=Be(),Me=Ve()??_e,Ge={activeModule:R(window.location.hash),connection:{apiBase:C,apiBaseDraft:C,autoRefresh:we()},sync:{status:"idle",lastError:null},snapshot:null,atendimento:{draft:{telefone:"",quantidadeGaloes:"1",atendenteId:"1",origemCanal:"MANUAL",sourceEventId:"",manualRequestId:"",externalCallId:"",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",latitude:"",longitude:""},lookupPhone:"",lookupPedidoId:"",sessionCases:[],activeCaseId:null,submitting:!1,syncingCaseId:null,lastError:null,lastSuccess:null},despacho:{routeStart:{status:"idle",lastError:null},lastRouteStart:null},entregador:{entregadorId:Me,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}},$=Z(Ge),w=X(e=>{$.setActiveModule(e)});let b;const I=Y({intervalMs:Ue,onTick:()=>{b.refreshSnapshot()}});b=K({root:x,router:w,store:$,persistApiBase:He,persistAutoRefresh:ze});$.subscribe(e=>{O(x,e),I.sync(e.connection.autoRefresh)});b.bind();O(x,$.getState());w.start();I.sync($.getState().connection.autoRefresh);b.refreshSnapshot();window.addEventListener("beforeunload",()=>{b.dispose(),I.stop()});function Ve(){try{const e=new URL(window.location.href),a=Number(e.searchParams.get("entregadorId"));return Number.isInteger(a)&&a>0?a:null}catch{return null}}
