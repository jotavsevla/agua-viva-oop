(function(){const a=document.createElement("link").relList;if(a&&a.supports&&a.supports("modulepreload"))return;for(const o of document.querySelectorAll('link[rel="modulepreload"]'))n(o);new MutationObserver(o=>{for(const i of o)if(i.type==="childList")for(const s of i.addedNodes)s.tagName==="LINK"&&s.rel==="modulepreload"&&n(s)}).observe(document,{childList:!0,subtree:!0});function t(o){const i={};return o.integrity&&(i.integrity=o.integrity),o.referrerPolicy&&(i.referrerPolicy=o.referrerPolicy),o.crossOrigin==="use-credentials"?i.credentials="include":o.crossOrigin==="anonymous"?i.credentials="omit":i.credentials="same-origin",i}function n(o){if(o.ep)return;o.ep=!0;const i=t(o);fetch(o.href,i)}})();const ye=["MANUAL","WHATSAPP","BINA_FIXO","TELEFONIA_FIXO"],Se=["NAO_INFORMADO","DINHEIRO","PIX","CARTAO","VALE"],Ce=["ASAP","HARD","FLEXIVEL"],Ge=new Set(["ENTREGUE","CANCELADO"]),K=new Set(["WHATSAPP","BINA_FIXO","TELEFONIA_FIXO"]);function d(e){return String(e??"").trim()}function Z(e){return d(e).toUpperCase()}function pe(e,a){const t=Number(e);if(!Number.isInteger(t)||t<=0)throw new Error(`${a} invalido`);return t}function me(e,a){const t=d(e).replace(",",".");if(!t)throw new Error(`${a} invalida`);const n=Number(t);if(!Number.isFinite(n))throw new Error(`${a} invalida`);return n}function ee(e){const a=Z(e);if(!a)return"";if(!ye.includes(a))throw new Error("origemCanal invalido");return a}function ae(e){const a=Z(e);if(!a)return"";if(!Se.includes(a))throw new Error("metodoPagamento invalido");return a}function te(e){const a=Z(e);if(!a)return"";if(a==="FLEX")return"FLEXIVEL";if(!Ce.includes(a))throw new Error("janelaTipo invalido");return a}function Re(){return{telefone:"",quantidadeGaloes:"1",atendenteId:"1",origemCanal:"MANUAL",sourceEventId:"",manualRequestId:U(),externalCallId:"",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",latitude:"",longitude:""}}function _e(e){const a=N(e?.draft),t=Ve(e?.sessionCases),n=e?.activeCaseId??null;return{draft:a,lookupPhone:d(e?.lookupPhone),lookupPedidoId:d(e?.lookupPedidoId),sessionCases:t,activeCaseId:t.some(o=>o.pedidoId===n)?n:null}}function Je(e){return{..._e(e),submitting:!1,syncingCaseId:null,lastError:null,lastSuccess:null}}function N(e){const a=Re(),t={...a,...e,telefone:d(e?.telefone),quantidadeGaloes:d(e?.quantidadeGaloes)||a.quantidadeGaloes,atendenteId:d(e?.atendenteId)||a.atendenteId,origemCanal:ee(d(e?.origemCanal))||a.origemCanal,sourceEventId:d(e?.sourceEventId),manualRequestId:d(e?.manualRequestId),externalCallId:d(e?.externalCallId),metodoPagamento:ae(d(e?.metodoPagamento))||a.metodoPagamento,janelaTipo:te(d(e?.janelaTipo))||a.janelaTipo,janelaInicio:d(e?.janelaInicio),janelaFim:d(e?.janelaFim),nomeCliente:d(e?.nomeCliente),endereco:d(e?.endereco),latitude:d(e?.latitude),longitude:d(e?.longitude)};return t.origemCanal==="MANUAL"&&!t.manualRequestId&&(t.manualRequestId=U()),t}function Ve(e){return Array.isArray(e)?e.filter(a=>!!(a&&Number.isInteger(a.pedidoId))).map(a=>({pedidoId:a.pedidoId,clienteId:Number.isInteger(a.clienteId)?a.clienteId:null,telefone:d(a.telefone),telefoneNormalizado:M(a.telefoneNormalizado||a.telefone),quantidadeGaloes:Number.isInteger(a.quantidadeGaloes)?a.quantidadeGaloes:0,atendenteId:Number.isInteger(a.atendenteId)?a.atendenteId:0,origemCanal:ee(a.origemCanal)||"MANUAL",metodoPagamento:ae(a.metodoPagamento)||"NAO_INFORMADO",janelaTipo:te(a.janelaTipo)||"ASAP",janelaInicio:d(a.janelaInicio),janelaFim:d(a.janelaFim),nomeCliente:d(a.nomeCliente),endereco:d(a.endereco),requestKey:d(a.requestKey)||null,clienteCriado:!!a.clienteCriado,idempotente:!!a.idempotente,statusAtual:d(a.statusAtual)||null,timeline:a.timeline||null,execucao:a.execucao||null,saldo:a.saldo||null,extrato:a.extrato||null,financeStatus:Xe(a.financeStatus),notes:Array.isArray(a.notes)?a.notes.map(t=>d(t)).filter(Boolean):[],error:d(a.error)||null,createdAt:d(a.createdAt)||new Date().toISOString(),updatedAt:d(a.updatedAt)||d(a.createdAt)||new Date().toISOString(),lastSyncAt:d(a.lastSyncAt)||null})).sort(Q):[]}function Xe(e){return e==="ok"||e==="unavailable"||e==="error"?e:"idle"}function U(){const e=new Date().toISOString().replace(/[-:.TZ]/g,"").slice(0,14),a=Math.random().toString(36).slice(2,8);return`manual-ui-${e}-${a}`}function ge(e){const a=N({telefone:d(e.get("telefone")),quantidadeGaloes:d(e.get("quantidadeGaloes")),atendenteId:d(e.get("atendenteId")),origemCanal:ee(d(e.get("origemCanal"))),sourceEventId:d(e.get("sourceEventId")),manualRequestId:d(e.get("manualRequestId")),externalCallId:d(e.get("externalCallId")),metodoPagamento:ae(d(e.get("metodoPagamento"))),janelaTipo:te(d(e.get("janelaTipo"))),janelaInicio:d(e.get("janelaInicio")),janelaFim:d(e.get("janelaFim")),nomeCliente:d(e.get("nomeCliente")),endereco:d(e.get("endereco")),latitude:d(e.get("latitude")),longitude:d(e.get("longitude"))});return a.origemCanal==="MANUAL"&&!a.manualRequestId&&(a.manualRequestId=U()),a}function ne(e){const a=N(e),t={telefone:d(a.telefone),quantidadeGaloes:pe(a.quantidadeGaloes,"quantidadeGaloes"),atendenteId:pe(a.atendenteId,"atendenteId")};if(!t.telefone)throw new Error("telefone obrigatorio");a.origemCanal&&(t.origemCanal=a.origemCanal),a.metodoPagamento&&(t.metodoPagamento=a.metodoPagamento),a.janelaTipo&&(t.janelaTipo=a.janelaTipo),a.sourceEventId&&(t.sourceEventId=a.sourceEventId),a.manualRequestId&&(t.manualRequestId=a.manualRequestId),a.externalCallId&&(t.externalCallId=a.externalCallId),a.janelaInicio&&(t.janelaInicio=a.janelaInicio),a.janelaFim&&(t.janelaFim=a.janelaFim),a.nomeCliente&&(t.nomeCliente=a.nomeCliente),a.endereco&&(t.endereco=a.endereco);const n=!!a.latitude,o=!!a.longitude;if(n!==o)throw new Error("latitude e longitude devem ser informadas juntas");return n&&o&&(t.latitude=me(a.latitude,"latitude"),t.longitude=me(a.longitude,"longitude")),Ke(t),Qe(t),{payload:t,idempotencyKey:We(t)}}function Ke(e){const a=e.origemCanal||"";if(a==="MANUAL"&&e.sourceEventId)throw new Error("sourceEventId nao pode ser usado com origemCanal=MANUAL");if(K.has(a)&&!e.sourceEventId)throw new Error(`sourceEventId obrigatorio para origemCanal=${a}`);if(K.has(a)&&e.manualRequestId)throw new Error(`manualRequestId so pode ser usado com origemCanal=MANUAL (recebido: ${a})`);if(!a&&e.sourceEventId&&e.manualRequestId)throw new Error("manualRequestId nao pode ser combinado com sourceEventId quando origemCanal estiver vazio");if(e.sourceEventId&&e.externalCallId&&e.sourceEventId!==e.externalCallId)throw new Error("sourceEventId diverge de externalCallId");if(e.manualRequestId&&e.externalCallId&&e.manualRequestId!==e.externalCallId)throw new Error("manualRequestId diverge de externalCallId")}function Qe(e){if(e.janelaTipo==="HARD"){if(!e.janelaInicio||!e.janelaFim)throw new Error("janelaTipo=HARD exige janelaInicio e janelaFim");return}if(e.janelaInicio||e.janelaFim)throw new Error("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD")}function We(e){return e.origemCanal==="MANUAL"?e.manualRequestId||e.externalCallId||null:K.has(e.origemCanal||"")?e.sourceEventId||e.externalCallId||null:e.sourceEventId||e.manualRequestId||e.externalCallId||null}function xe(e,a){const t=[];try{ne(e)}catch(o){t.push(o instanceof Error?o.message:"payload invalido")}const n=M(e.telefone);if(n){const o=a.find(i=>i.telefoneNormalizado===n&&!ta(i));o&&t.push(`Ja existe pedido em acompanhamento na sessao para este telefone (#${o.pedidoId}, ${o.statusAtual||"sem status"}).`)}return z(t)}function Ye(e){const a=[];return e.metodoPagamento==="VALE"&&a.push("Checkout em vale depende de saldo suficiente no cliente e pode ser bloqueado pelo backend."),e.origemCanal==="MANUAL"&&!e.nomeCliente&&a.push("Sem nome do cliente, o pedido entra, mas o atendimento perde contexto para o despacho."),e.janelaTipo==="HARD"&&a.push("Pedidos HARD devem entrar com horario fechado para evitar retrabalho no despacho."),z(a)}function M(e){return String(e??"").replace(/\D/g,"")}function Pe(e){const a=M(e);return a.length===11?`(${a.slice(0,2)}) ${a.slice(2,7)}-${a.slice(7)}`:a.length===10?`(${a.slice(0,2)}) ${a.slice(2,6)}-${a.slice(6)}`:d(e)}function Ze(e,a){const t=N(e),n=new Date().toISOString(),{payload:o,idempotencyKey:i}=ne(t);return{pedidoId:a.pedidoId,clienteId:a.clienteId,telefone:o.telefone,telefoneNormalizado:a.telefoneNormalizado,quantidadeGaloes:o.quantidadeGaloes,atendenteId:o.atendenteId,origemCanal:o.origemCanal||"MANUAL",metodoPagamento:o.metodoPagamento||"NAO_INFORMADO",janelaTipo:o.janelaTipo||"ASAP",janelaInicio:o.janelaInicio||"",janelaFim:o.janelaFim||"",nomeCliente:o.nomeCliente||"",endereco:o.endereco||"",requestKey:i,clienteCriado:a.clienteCriado,idempotente:a.idempotente,statusAtual:"PENDENTE",timeline:null,execucao:null,saldo:null,extrato:null,financeStatus:"idle",notes:[],error:null,createdAt:n,updatedAt:n,lastSyncAt:null}}function fe(e,a){const t=a.timeline===void 0?e.timeline:a.timeline,n=a.execucao===void 0?e.execucao:a.execucao;return{...e,timeline:t,execucao:n,saldo:a.saldo===void 0?e.saldo:a.saldo,extrato:a.extrato===void 0?e.extrato:a.extrato,financeStatus:a.financeStatus||e.financeStatus,statusAtual:t?.statusAtual||n?.statusPedido||e.statusAtual,error:a.error===void 0?e.error:a.error,notes:a.notes?z([...e.notes,...a.notes]):e.notes,updatedAt:new Date().toISOString(),lastSyncAt:new Date().toISOString()}}function ea(e){const a=N(e);return{...Re(),quantidadeGaloes:a.quantidadeGaloes,atendenteId:a.atendenteId,origemCanal:"MANUAL",metodoPagamento:a.metodoPagamento,janelaTipo:a.janelaTipo,janelaInicio:a.janelaTipo==="HARD"?a.janelaInicio:"",janelaFim:a.janelaTipo==="HARD"?a.janelaFim:"",manualRequestId:U()}}function V(e,a){const t=e.findIndex(o=>o.pedidoId===a.pedidoId);if(t===-1)return[...e,a].sort(Q);const n=[...e];return n[t]={...n[t],...a,notes:z([...n[t].notes||[],...a.notes||[]])},n.sort(Q)}function aa(e){return e.sessionCases.find(a=>a.pedidoId===e.activeCaseId)||null}function je(e,a){const t=M(a);return t?e.sessionCases.filter(n=>n.telefoneNormalizado.includes(t)):e.sessionCases}function ta(e){return Ge.has(String(e.statusAtual||"").toUpperCase())}function Te(e){return e.janelaTipo==="HARD"?e.janelaInicio&&e.janelaFim?`HARD ${e.janelaInicio}-${e.janelaFim}`:"HARD incompleta":e.janelaTipo==="FLEXIVEL"?"Flexivel":"ASAP"}function na(e){if(!e)return{tone:"muted",stage:"Sem pedido em foco",detail:"Crie um novo pedido ou selecione um atendimento da sessao para montar o handoff.",action:"Nada para repassar ao despacho por enquanto."};const a=String(e.statusAtual||"").toUpperCase(),t=String(e.execucao?.camada||"").toUpperCase();return a==="CANCELADO"?{tone:"danger",stage:"Atendimento interrompido",detail:"O pedido foi cancelado e nao deve seguir para a operacao sem nova triagem.",action:"Registrar motivo com clareza e orientar novo contato se necessario."}:a==="ENTREGUE"?{tone:"ok",stage:"Ciclo encerrado",detail:"O pedido ja foi concluido em campo.",action:"Encerrar o caso no atendimento e manter apenas rastreabilidade."}:t==="PRIMARIA_EM_EXECUCAO"?{tone:"info",stage:"Em execucao",detail:"O pedido ja esta com a operacao de entrega, com rota primaria ativa.",action:"Atendimento acompanha apenas excecoes; despacho ja recebeu o handoff."}:t==="SECUNDARIA_CONFIRMADA"?{tone:"warn",stage:"Aguardando inicio de rota",detail:"O pedido foi encaixado na secundaria e depende de acao do despacho para avancar.",action:"Avisar o despacho sobre rota pronta e janela do cliente."}:a==="CONFIRMADO"?{tone:"warn",stage:"Confirmado para despacho",detail:"O pedido ja saiu do atendimento, mas ainda precisa ser roteirizado.",action:"Repassar prioridade, forma de pagamento e observacoes do cadastro."}:{tone:"info",stage:"Entrou na fila",detail:"Pedido registrado e aguardando a cadencia normal do despacho.",action:"Checar o cockpit operacional para acompanhar a entrada nas filas."}}function oa(e){const a=new Date().toISOString();return{pedidoId:e,clienteId:null,telefone:"",telefoneNormalizado:"",quantidadeGaloes:0,atendenteId:0,origemCanal:"MANUAL",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",requestKey:null,clienteCriado:!1,idempotente:!1,statusAtual:null,timeline:null,execucao:null,saldo:null,extrato:null,financeStatus:"idle",notes:["Pedido importado por consulta manual."],error:null,createdAt:a,updatedAt:a,lastSyncAt:null}}function Q(e,a){return a.updatedAt.localeCompare(e.updatedAt)}function z(e){return[...new Set(e.map(a=>d(a)).filter(Boolean))]}const ia=20,ra=8;class Ne extends Error{status;detail;constructor(a,t,n=null){super(a),this.name="ApiError",this.status=t,this.detail=n}}function sa(e,a){return`${String(e).replace(/\/+$/,"")}${a}`}async function C(e,a,t={}){const n=await fetch(sa(e,a),{method:t.method||"GET",headers:{...t.body?{"Content-Type":"application/json"}:{},...t.headers||{}},body:t.body?JSON.stringify(t.body):void 0}),o=await n.json().catch(()=>({}));if(!n.ok)throw new Ne(o.erro||o.message||`HTTP ${n.status}`,n.status,o.detalhe||null);return o}function la(){return{health:"unknown",painel:"unknown",eventos:"unknown",mapa:"unknown"}}async function da(e){const a=la(),t=[],[n,o,i,s]=await Promise.allSettled([C(e,"/health"),C(e,"/api/operacao/painel"),C(e,`/api/operacao/eventos?limite=${ia}`),C(e,"/api/operacao/mapa")]),$=F(n,"health",a,t),v=F(o,"painel",a,t),A=F(i,"eventos",a,t),I=F(s,"mapa",a,t);if(!$&&!v&&!A&&!I)throw new Error("Nao foi possivel carregar nenhum read model operacional.");return{health:$,painel:v,eventos:A,mapa:I,readiness:a,partialErrors:t,fetchedAt:new Date().toISOString()}}function F(e,a,t,n){return e.status==="fulfilled"?(t[a]="ok",e.value):(t[a]="error",n.push(`${a}: ${e.reason instanceof Error?e.reason.message:"falha"}`),null)}function he(e){return e instanceof Ne&&[400,404,405,501].includes(e.status)}async function ca(e,a,t){return C(e,"/api/atendimento/pedidos",{method:"POST",headers:t?{"Idempotency-Key":t}:void 0,body:a})}async function ua(e,a){return C(e,`/api/pedidos/${a}/timeline`)}async function pa(e,a){return C(e,`/api/pedidos/${a}/execucao`)}async function ma(e,a){return C(e,`/api/financeiro/clientes/${a}/saldo`)}async function ga(e,a,t=ra){return C(e,`/api/financeiro/clientes/${a}/extrato?limit=${t}`)}async function fa(e,a){return C(e,"/api/operacao/rotas/prontas/iniciar",{method:"POST",body:{entregadorId:a}})}function x(e){return e instanceof Error?e.message:"Falha ao sincronizar a operacao."}function ha(e){const{root:a,router:t,store:n,persistApiBase:o,persistAutoRefresh:i,persistAtendimentoState:s}=e,$=()=>{const p=n.getState().atendimento;s({draft:p.draft,lookupPhone:p.lookupPhone,lookupPedidoId:p.lookupPedidoId,sessionCases:p.sessionCases,activeCaseId:p.activeCaseId})},v=(p,h=!0)=>{n.updateAtendimento(p,{notify:h}),$()},A=async()=>{if(n.getState().sync.status!=="loading"){n.startSync();try{const p=await da(n.getState().connection.apiBase);n.finishSync(p)}catch(p){n.failSync(x(p))}}},I=async p=>{if(!Number.isInteger(p)||p<=0)return;let h=n.getState().atendimento.sessionCases.find(u=>u.pedidoId===p);if(h)v(u=>({...u,activeCaseId:p,lastError:null}));else{const u=oa(p);h=u,v(m=>({...m,sessionCases:V(m.sessionCases,u),activeCaseId:p,lastError:null}))}v(u=>({...u,syncingCaseId:p}));try{const u=n.getState().atendimento.sessionCases.find(O=>O.pedidoId===p);if(!u)throw new Error(`Pedido #${p} nao encontrado na sessao de atendimento.`);const[m,f,b,j]=await Promise.allSettled([ua(n.getState().connection.apiBase,p),pa(n.getState().connection.apiBase,p),u.clienteId?ma(n.getState().connection.apiBase,u.clienteId):Promise.resolve(null),u.clienteId?ga(n.getState().connection.apiBase,u.clienteId):Promise.resolve(null)]),q=[];let P=u.financeStatus,_=null;const J=fe(u,{timeline:m.status==="fulfilled"?m.value:u.timeline,execucao:f.status==="fulfilled"?f.value:u.execucao});m.status==="rejected"&&q.push(x(m.reason)),f.status==="rejected"&&q.push(x(f.reason)),b.status==="fulfilled"&&b.value?P="ok":b.status==="rejected"&&(he(b.reason)?(P="unavailable",q.push("Saldo/extrato nao estao disponiveis nesta base da app.")):(P="error",_=x(b.reason))),j.status==="fulfilled"&&j.value?P="ok":j.status==="rejected"&&(he(j.reason)?P="unavailable":P!=="unavailable"&&(P="error",_=x(j.reason)));const He=fe(J,{saldo:b.status==="fulfilled"?b.value:J.saldo,extrato:j.status==="fulfilled"?j.value:J.extrato,financeStatus:P,error:_,notes:q});v(O=>({...O,syncingCaseId:null,lastSuccess:`Pedido #${p} sincronizado com timeline e execucao.`,sessionCases:V(O.sessionCases,He)}))}catch(u){v(m=>({...m,syncingCaseId:null,lastError:x(u)}))}},S=async p=>{const h=ge(new FormData(p));v(m=>({...m,draft:h,lastError:null,lastSuccess:null}));let u;try{u=ne(h)}catch(m){v(f=>({...f,lastError:x(m)}));return}v(m=>({...m,submitting:!0}));try{const m=await ca(n.getState().connection.apiBase,u.payload,u.idempotencyKey),f=Ze(h,m);v(b=>({...b,submitting:!1,draft:ea(b.draft),activeCaseId:m.pedidoId,lastSuccess:`Pedido #${m.pedidoId} criado para cliente ${m.clienteId}.`,sessionCases:V(b.sessionCases,f)})),await I(m.pedidoId),await A()}catch(m){v(f=>({...f,submitting:!1,lastError:x(m)}))}},l=async p=>{const h=new FormData(p),u=String(h.get("lookupPhone")||"").trim(),m=Number(String(h.get("lookupPedidoId")||"").trim());if(v(b=>({...b,lookupPhone:u,lookupPedidoId:Number.isInteger(m)&&m>0?String(m):"",lastError:null})),m>0){await I(m);return}const f=je(n.getState().atendimento,u);if(f.length===0){v(b=>({...b,lastError:"Nenhum atendimento da sessao corresponde a esse telefone."}));return}v(b=>({...b,activeCaseId:f[0].pedidoId}))},E=async()=>{await A(),n.getState().activeModule==="atendimento"&&n.getState().atendimento.activeCaseId&&await I(n.getState().atendimento.activeCaseId)},G=async p=>{if(window.confirm(`Iniciar a proxima rota pronta do entregador ${p}?`)){n.startDespachoRouteStart();try{const u=await fa(n.getState().connection.apiBase,p);n.finishDespachoRouteStart(u),await A()}catch(u){n.failDespachoRouteStart(x(u))}}},ce=p=>{const u=(p.target instanceof HTMLElement?p.target:null)?.closest("[data-action]");if(!u||!a.contains(u))return;const m=u.dataset.action;if(m==="save-api-base"){n.commitConnection(),o(n.getState().connection.apiBase),E();return}if(m==="refresh-snapshot"){E();return}if(m==="navigate"){const f=u.dataset.moduleId;f&&(t.navigate(f),n.setActiveModule(f),E());return}if(m==="focus-atendimento-case"){const f=Number(u.dataset.pedidoId);if(!Number.isInteger(f)||f<=0)return;v(b=>({...b,activeCaseId:f,lastError:null}));return}if(m==="refresh-atendimento-case"){const f=Number(u.dataset.pedidoId);if(!Number.isInteger(f)||f<=0)return;I(f);return}if(m==="start-prepared-route"){const f=Number(u.dataset.entregadorId);if(!Number.isInteger(f)||f<=0){n.failDespachoRouteStart("Entregador invalido para iniciar a rota.");return}G(f)}},D=p=>{const h=p.target;if(!(h instanceof HTMLInputElement||h instanceof HTMLSelectElement||h instanceof HTMLTextAreaElement)||!a.contains(h))return;if(h instanceof HTMLInputElement&&h.id==="api-base-input"){n.setConnectionDraft(h.value);return}if(h instanceof HTMLInputElement&&h.id==="auto-refresh-input"){const f=!!h.checked;n.setAutoRefresh(f),i(f);return}const u=h.closest("form"),m=p.type==="change";if(u?.id==="atendimento-form"){const f=ge(new FormData(u));v(b=>({...b,draft:f}),m);return}if(u?.id==="atendimento-lookup-form"){const f=new FormData(u);v(b=>({...b,lookupPhone:String(f.get("lookupPhone")||"").trim(),lookupPedidoId:String(f.get("lookupPedidoId")||"").trim()}),m)}},ue=p=>{const h=p.target;if(!(!(h instanceof HTMLFormElement)||!a.contains(h))){if(h.id==="atendimento-form"){p.preventDefault(),S(h);return}h.id==="atendimento-lookup-form"&&(p.preventDefault(),l(h))}};return{bind(){a.addEventListener("click",ce),a.addEventListener("input",D),a.addEventListener("change",D),a.addEventListener("submit",ue)},refreshSnapshot:E,dispose(){a.removeEventListener("click",ce),a.removeEventListener("input",D),a.removeEventListener("change",D),a.removeEventListener("submit",ue),t.dispose()}}}const oe=[{id:"cockpit",hash:"#/cockpit",label:"Cockpit",description:"Visao consolidada da operacao, dos alertas e dos read models.",status:"active"},{id:"atendimento",hash:"#/atendimento",label:"Atendimento",description:"Busca de cliente na sessao, criacao segura de pedido e handoff conectado ao despacho.",status:"active"},{id:"despacho",hash:"#/despacho",label:"Despacho",description:"Cockpit principal para fila operacional, camadas de frota, risco e acoes de saida.",status:"active"},{id:"entregador",hash:"#/entregador",label:"Entregador",description:"Base reservada para roteiro, progresso de rota, deep link e eventos terminais.",status:"planned"}];function B(e){const a=oe.find(t=>t.id===e);if(!a)throw new Error(`Modulo desconhecido: ${e}`);return a}function ve(e){return B(e).hash}function va(e){return oe.some(a=>a.id===e)}function W(e){const a=String(e||"").replace(/^#\/?/,"").trim().toLowerCase();return va(a)?a:"cockpit"}function $a(e){const a=e.targetWindow??window;let t=null;const n=()=>{t!==null&&(a.clearInterval(t),t=null)},o=()=>{n(),t=a.setInterval(e.onTick,e.intervalMs)};return{sync(i){if(!i){n();return}o()},stop(){n()}}}function ba(e,a=window){let t=W(a.location.hash);const n=()=>{const o=W(a.location.hash);o!==t&&(t=o,e(o))};return{getCurrentModule(){return t},navigate(o){t=o;const i=ve(o);a.location.hash!==i&&(a.location.hash=i)},start(){a.addEventListener("hashchange",n);const o=ve(t);a.location.hash!==o&&(a.location.hash=o)},dispose(){a.removeEventListener("hashchange",n)}}}function Aa(e){let a=e;const t=new Set,n=()=>{t.forEach(i=>i(a))},o=(i,s={})=>{a=i(a),s.notify!==!1&&n()};return{getState(){return a},subscribe(i){return t.add(i),()=>{t.delete(i)}},setConnectionDraft(i){o(s=>({...s,connection:{...s.connection,apiBaseDraft:i}}),{notify:!1})},commitConnection(){o(i=>{const s=i.connection.apiBaseDraft.trim()||i.connection.apiBase;return{...i,connection:{...i.connection,apiBase:s,apiBaseDraft:s}}})},setAutoRefresh(i){o(s=>({...s,connection:{...s.connection,autoRefresh:i}}))},setActiveModule(i){o(s=>({...s,activeModule:i}))},updateAtendimento(i,s){o($=>({...$,atendimento:i($.atendimento)}),s)},setEntregadorId(i){o(s=>({...s,entregador:{...s.entregador,entregadorId:i,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}}))},startSync(){o(i=>({...i,sync:{...i.sync,status:"loading",lastError:null}}))},finishSync(i){o(s=>({...s,snapshot:i,sync:{status:"ready",lastError:null}}))},failSync(i){o(s=>({...s,sync:{status:"error",lastError:i}}))},startDespachoRouteStart(){o(i=>({...i,despacho:{...i.despacho,routeStart:{status:"loading",lastError:null},lastRouteStart:null}}))},finishDespachoRouteStart(i){o(s=>({...s,despacho:{routeStart:{status:"ready",lastError:null},lastRouteStart:{tone:i.idempotente?"warn":"ok",title:i.idempotente?"Acao reconhecida como idempotente":"Rota pronta iniciada",detail:`R${i.rotaId} disparada para o pedido ${i.pedidoId} e entrega ${i.entregaId}.`,payload:i}}}))},failDespachoRouteStart(i){o(s=>({...s,despacho:{routeStart:{status:"error",lastError:i},lastRouteStart:{tone:"danger",title:"Falha ao iniciar rota pronta",detail:i,payload:null}}}))},startEntregadorSync(){o(i=>({...i,entregador:{...i.entregador,sync:{status:"loading",lastError:null}}}))},finishEntregadorSync(i,s){o($=>({...$,entregador:{...$.entregador,roteiro:i,fetchedAt:s,sync:{status:"ready",lastError:null}}}))},failEntregadorSync(i){o(s=>({...s,entregador:{...s.entregador,sync:{status:"error",lastError:i}}}))},startEntregadorAction(){o(i=>({...i,entregador:{...i.entregador,action:{status:"loading",lastError:null}}}))},finishEntregadorAction(i){o(s=>({...s,entregador:{...s.entregador,action:{status:"ready",lastError:null},lastAction:i}}))},failEntregadorAction(i){o(s=>({...s,entregador:{...s.entregador,action:{status:"error",lastError:i},lastAction:{tone:"danger",title:"Acao rejeitada",detail:i,payload:null}}}))}}}function c(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function H(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function y(e,a){return`<span class="pill ${a}">${c(e)}</span>`}function X(e,a,t){return`<option value="${c(e)}" ${e===t?"selected":""}>${c(a)}</option>`}function ie(e){const a=String(e.statusAtual||"").toUpperCase();return a==="ENTREGUE"?"ok":a==="CANCELADO"?"danger":a==="CONFIRMADO"?"warn":a==="EM_ROTA"?"info":"muted"}function Ia(e){const a=e.snapshot?.painel;return`
    <div class="atendimento-pulse-grid">
      ${[{label:"Fila nova",value:String(a?.filas.pendentesElegiveis.length??"-"),copy:"Pedidos que ainda vao entrar em triagem."},{label:"Rotas prontas",value:String(a?.rotas.planejadas.length??"-"),copy:"Demandas ja comprometidas aguardando acao do despacho."},{label:"Casos na sessao",value:String(e.atendimento.sessionCases.length),copy:"Atendimentos recentes preservados localmente para handoff."},{label:"Ultima leitura",value:e.snapshot?.fetchedAt?H(e.snapshot.fetchedAt):"-",copy:"Base operacional usada como pano de fundo do atendimento."}].map(n=>`
            <article class="pulse-card atendimento-pulse-card">
              <p class="label">${c(n.label)}</p>
              <strong>${c(n.value)}</strong>
              <p>${c(n.copy)}</p>
            </article>
          `).join("")}
    </div>
  `}function Ea(e){const a=xe(e.atendimento.draft,e.atendimento.sessionCases),t=Ye(e.atendimento.draft),n=[];return a.length>0&&n.push(`
      <div class="notice notice-danger">
        <strong>Bloqueios antes de enviar:</strong> ${c(a.join(" | "))}
      </div>
    `),t.length>0&&n.push(`
      <div class="notice notice-warn">
        <strong>Pontos de atencao:</strong> ${c(t.join(" | "))}
      </div>
    `),e.atendimento.lastError&&n.push(`
      <div class="notice notice-danger">
        <strong>Falha no atendimento:</strong> ${c(e.atendimento.lastError)}
      </div>
    `),e.atendimento.lastSuccess&&n.push(`
      <div class="notice notice-ok">
        <strong>Atendimento registrado:</strong> ${c(e.atendimento.lastSuccess)}
      </div>
    `),e.snapshot?.partialErrors&&e.snapshot.partialErrors.length>0&&n.push(`
      <div class="notice notice-warn">
        <strong>Leitura operacional parcial:</strong> ${c(e.snapshot.partialErrors.join(" | "))}
      </div>
    `),n.join("")}function ya(e){const a=e.atendimento.draft,t=xe(a,e.atendimento.sessionCases),n=a.janelaTipo==="HARD",o=a.origemCanal==="MANUAL",i=a.origemCanal==="WHATSAPP"||a.origemCanal==="BINA_FIXO"||a.origemCanal==="TELEFONIA_FIXO";return`
    <section class="panel atendimento-form-panel">
      <div class="panel-header">
        <div>
          <h2>Novo atendimento</h2>
          <p class="section-copy">Entrada guiada para pedido telefonico com regras explicitas e rastro de idempotencia.</p>
        </div>
        ${y(e.atendimento.submitting?"enviando":"pronto para envio",e.atendimento.submitting?"warn":"ok")}
      </div>
      <form id="atendimento-form" class="atendimento-form">
        <div class="form-grid form-grid-primary">
          <label class="field">
            <span>Telefone</span>
            <input name="telefone" type="tel" value="${c(a.telefone)}" placeholder="(38) 99876-1234" required />
          </label>
          <label class="field">
            <span>Quantidade de galoes</span>
            <input name="quantidadeGaloes" type="number" min="1" step="1" value="${c(a.quantidadeGaloes)}" required />
          </label>
          <label class="field">
            <span>Atendente ID</span>
            <input name="atendenteId" type="number" min="1" step="1" value="${c(a.atendenteId)}" required />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Metodo de pagamento</span>
            <select name="metodoPagamento">
              ${Se.map(s=>X(s,s.replace("_"," "),a.metodoPagamento)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Janela</span>
            <select name="janelaTipo">
              ${Ce.map(s=>X(s,s,a.janelaTipo)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Origem do canal</span>
            <select name="origemCanal">
              ${ye.map(s=>X(s,s.replace("_"," "),a.origemCanal)).join("")}
            </select>
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${n?"":"field-disabled"}">
            <span>Janela inicio</span>
            <input name="janelaInicio" type="time" value="${c(a.janelaInicio)}" ${n?"":"disabled"} />
          </label>
          <label class="field ${n?"":"field-disabled"}">
            <span>Janela fim</span>
            <input name="janelaFim" type="time" value="${c(a.janelaFim)}" ${n?"":"disabled"} />
          </label>
          <label class="field">
            <span>Nome do cliente</span>
            <input name="nomeCliente" type="text" value="${c(a.nomeCliente)}" placeholder="Condominio Horizonte" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Endereco</span>
            <input name="endereco" type="text" value="${c(a.endereco)}" placeholder="Rua da Operacao, 99" />
          </label>
          <label class="field">
            <span>Latitude</span>
            <input name="latitude" type="text" value="${c(a.latitude)}" placeholder="-16.7200" />
          </label>
          <label class="field">
            <span>Longitude</span>
            <input name="longitude" type="text" value="${c(a.longitude)}" placeholder="-43.8600" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${i?"":"field-disabled"}">
            <span>sourceEventId</span>
            <input name="sourceEventId" type="text" value="${c(a.sourceEventId)}" placeholder="evt-whatsapp-001" ${i?"":"disabled"} />
          </label>
          <label class="field ${o?"":"field-disabled"}">
            <span>manualRequestId</span>
            <input name="manualRequestId" type="text" value="${c(a.manualRequestId)}" placeholder="manual-ui-..." ${o?"":"disabled"} />
          </label>
          <label class="field">
            <span>externalCallId</span>
            <input name="externalCallId" type="text" value="${c(a.externalCallId)}" placeholder="call-center-001" />
          </label>
        </div>

        <div class="form-actions">
          <button class="button primary" type="submit" ${e.atendimento.submitting||t.length>0?"disabled":""}>
            ${e.atendimento.submitting?"Registrando...":"Registrar pedido"}
          </button>
          <p class="form-footnote">O envio usa a API real e preserva uma chave de idempotencia para retries seguros.</p>
        </div>
      </form>
    </section>
  `}function Sa(e){return e.financeStatus==="unavailable"?'<div class="notice notice-warn"><strong>Financeiro nao acoplado:</strong> a base atual nao expoe saldo/extrato do cliente nesta app.</div>':e.financeStatus==="error"?'<div class="notice notice-danger"><strong>Financeiro com falha:</strong> nao foi possivel sincronizar saldo/extrato agora.</div>':!e.saldo&&!e.extrato?'<p class="empty-copy">Saldo e extrato ainda nao sincronizados para este cliente.</p>':`
    <div class="finance-grid">
      <article class="metric-card finance-card">
        <p class="metric-label">Saldo de vales</p>
        <p class="metric-value">${c(e.saldo?.quantidade??"-")}</p>
      </article>
      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Extrato recente</h3>
          ${y(e.extrato?String(e.extrato.itens.length):"0","info")}
        </div>
        <div class="timeline-list">
          ${e.extrato&&e.extrato.itens.length>0?e.extrato.itens.slice(0,5).map(Ca).join(""):'<p class="empty-copy">Nenhum movimento recente retornado.</p>'}
        </div>
      </section>
    </div>
  `}function Ca(e){return`
    <article class="timeline-item compact">
      <div class="timeline-topline">
        <strong>${c(e.tipo)}</strong>
        ${y(`saldo ${e.saldoApos}`,e.tipo==="CREDITO"?"ok":"warn")}
      </div>
      <p class="mono">${c(e.quantidade)} galao(oes) · ${c(H(e.data))}</p>
      <p class="empty-copy">${c(e.observacao||e.registradoPor)}</p>
    </article>
  `}function Ra(e){return`
    <section class="timeline-shell">
      <div class="context-block-header">
        <h3>Timeline do pedido</h3>
        ${y(e.statusAtual||"sem status",ie(e))}
      </div>
      <div class="timeline-list">
        ${e.timeline&&e.timeline.eventos.length>0?e.timeline.eventos.map(a=>`
                    <article class="timeline-item">
                      <div class="timeline-topline">
                        <strong>${c(a.deStatus)} → ${c(a.paraStatus)}</strong>
                        ${y(a.origem,"muted")}
                      </div>
                      <p class="mono">${c(H(a.timestamp))}</p>
                      ${a.observacao?`<p>${c(a.observacao)}</p>`:""}
                    </article>
                  `).join(""):'<p class="empty-copy">Timeline ainda nao carregada para este pedido.</p>'}
      </div>
    </section>
  `}function xa(e){const a=aa(e.atendimento),t=je(e.atendimento,e.atendimento.lookupPhone),n=na(a),o=a?Sa(a):'<p class="empty-copy">Selecione um atendimento para ver contexto financeiro e historico.</p>',i=a?Ra(a):'<p class="empty-copy">Nenhum atendimento em foco.</p>',s=a&&e.atendimento.syncingCaseId===a.pedidoId;return`
    <section class="panel atendimento-context-panel">
      <div class="panel-header">
        <div>
          <h2>Contexto e handoff</h2>
          <p class="section-copy">Consulta rapida por pedido, ressincronizacao com a API e passagem clara para o despacho.</p>
        </div>
        ${y(a?`pedido #${a.pedidoId}`:"sem foco",a?ie(a):"muted")}
      </div>

      <form id="atendimento-lookup-form" class="lookup-form">
        <label class="field">
          <span>Buscar por telefone da sessao</span>
          <input name="lookupPhone" type="text" value="${c(e.atendimento.lookupPhone)}" placeholder="38998761234" />
        </label>
        <label class="field">
          <span>Consultar pedidoId na API</span>
          <input name="lookupPedidoId" type="number" min="1" value="${c(e.atendimento.lookupPedidoId)}" placeholder="8421" />
        </label>
        <div class="toolbar-actions lookup-actions">
          <button class="button secondary" type="submit">Buscar contexto</button>
          ${a?`<button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${a.pedidoId}" type="button" ${s?"disabled":""}>${s?"Atualizando...":"Atualizar pedido"}</button>`:""}
        </div>
      </form>

      ${a?`
            <article class="handoff-card tone-${n.tone}">
              <div class="priority-topline">
                ${y(n.stage,n.tone)}
                <strong>${c(a.nomeCliente||Pe(a.telefone)||`Pedido #${a.pedidoId}`)}</strong>
              </div>
              <p>${c(n.detail)}</p>
              <p class="priority-action">${c(n.action)}</p>
              <div class="context-metrics">
                <article class="metric-card compact">
                  <p class="metric-label">Pedido</p>
                  <p class="metric-value small">#${c(a.pedidoId)}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Cliente</p>
                  <p class="metric-value small">${c(a.clienteId??"-")}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Janela</p>
                  <p class="metric-value small">${c(Te(a))}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Execucao</p>
                  <p class="metric-value small">${c(a.execucao?.camada||a.statusAtual||"-")}</p>
                </article>
              </div>
              <div class="tag-row">
                ${a.requestKey?y(`key ${a.requestKey}`,"muted"):""}
                ${a.execucao?.rotaId?y(`rota ${a.execucao.rotaId}`,"info"):""}
                ${a.execucao?.entregaId?y(`entrega ${a.execucao.entregaId}`,"info"):""}
                ${y(a.metodoPagamento||"NAO_INFORMADO",a.metodoPagamento==="VALE"?"warn":"muted")}
                ${a.clienteCriado?y("cliente criado agora","ok"):""}
                ${a.idempotente?y("retry idempotente","info"):""}
              </div>
              ${a.error?`<div class="notice notice-danger"><strong>Ultimo erro:</strong> ${c(a.error)}</div>`:""}
              ${a.notes.length>0?`<div class="notice notice-warn"><strong>Notas de contexto:</strong> ${c(a.notes.join(" | "))}</div>`:""}
              <div class="toolbar-actions handoff-actions">
                <button class="button primary" type="button" data-action="navigate" data-module-id="cockpit">Abrir cockpit operacional</button>
              </div>
            </article>
          `:""}

      <div class="context-stack">
        ${i}
        ${o}
      </div>

      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Atendimentos encontrados</h3>
          ${y(String(t.length),"muted")}
        </div>
        <div class="session-list">
          ${t.length>0?t.map($=>Pa($,e.atendimento.activeCaseId,e.atendimento.syncingCaseId)).join(""):'<p class="empty-copy">Nenhum atendimento da sessao corresponde a essa busca.</p>'}
        </div>
      </section>
    </section>
  `}function Pa(e,a,t){const n=ie(e),o=a===e.pedidoId,i=t===e.pedidoId;return`
    <article class="session-card ${o?"is-active":""}">
      <div class="queue-card-header">
        <strong>${c(e.nomeCliente||Pe(e.telefone)||`Pedido #${e.pedidoId}`)}</strong>
        ${y(e.statusAtual||"sem status",n)}
      </div>
      <p class="mono">Pedido #${c(e.pedidoId)} · cliente ${c(e.clienteId??"-")}</p>
      <p class="empty-copy">${c(Te(e))} · ${c(e.metodoPagamento||"NAO_INFORMADO")}</p>
      <p class="empty-copy">Atualizado ${c(H(e.lastSyncAt||e.updatedAt))}</p>
      <div class="toolbar-actions inline-actions">
        <button class="button secondary" data-action="focus-atendimento-case" data-pedido-id="${e.pedidoId}" type="button">
          ${o?"Em foco":"Trazer para foco"}
        </button>
        <button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${e.pedidoId}" type="button" ${i?"disabled":""}>
          ${i?"Atualizando...":"Atualizar"}
        </button>
      </div>
    </article>
  `}function ja(e){return`
    <section class="atendimento-workspace">
      ${Ia(e)}
      ${Ea(e)}
      <div class="atendimento-grid">
        ${ya(e)}
        ${xa(e)}
      </div>
    </section>
  `}function r(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function g(e,a){return`<span class="pill ${a}">${r(e)}</span>`}function Ta(e){return`
    <article class="signal-card tone-${e.tone}">
      <p class="panel-kicker">${r(e.label)}</p>
      <strong>${r(e.value)}</strong>
      <p>${r(e.detail)}</p>
    </article>
  `}function Na(e){return`
    <article class="metric-card tone-${e.tone}">
      <p class="metric-label">${r(e.label)}</p>
      <p class="metric-value">${r(e.value)}</p>
      <p class="metric-detail">${r(e.detail)}</p>
    </article>
  `}function ka(e){return`
    <article class="priority-card tone-${e.tone}">
      <div class="priority-topline">
        ${g(e.badge,e.tone)}
        <strong>${r(e.title)}</strong>
      </div>
      <p>${r(e.detail)}</p>
      <p class="priority-action">${r(e.action)}</p>
    </article>
  `}function $e(e){return`
    <article class="route-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${r(e.title)}</p>
          <p class="card-copy">${r(e.detail)}</p>
        </div>
        ${g(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="route-stat-row mono">
        ${e.meta.map(a=>`<span>${r(a)}</span>`).join("")}
      </div>
    </article>
  `}function La(e){return`
    <article class="queue-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${r(e.title)}</p>
          <p class="card-copy">${r(e.summary)}</p>
        </div>
        ${g(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="queue-card-stats mono">
        ${e.lines.map(a=>`<span>${r(a)}</span>`).join("")}
      </div>
    </article>
  `}function Da(e){return`
    <section class="queue-lane tone-${e.tone}">
      <div class="queue-lane-header">
        <div>
          <p class="panel-kicker">Etapa ${r(e.step)}</p>
          <h3>${r(e.title)}</h3>
          <p class="queue-lane-copy">${r(e.summary)}</p>
        </div>
        ${g(`${e.count} item(ns)`,e.tone)}
      </div>
      <div class="queue-list">
        ${e.cards.length?e.cards.map(La).join(""):`<p class="empty-copy">${r(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function qa(e){return`
    <article class="readiness-item tone-${e.tone}">
      <p class="panel-kicker">${r(e.label)}</p>
      <strong>${r(e.title)}</strong>
      <p>${r(e.detail)}</p>
    </article>
  `}function Oa(e){return`
    <article class="event-item tone-${e.tone}">
      <div class="event-topline">
        <div>
          <p class="card-title">${r(e.title)}</p>
          <p class="card-copy">${r(e.subject)}</p>
        </div>
        ${g(e.badgeLabel,e.badgeTone)}
      </div>
      <p>${r(e.detail)}</p>
      <p class="event-meta mono">${r(e.meta)}</p>
    </article>
  `}function Fa(e){return`
    <article class="micro-card tone-${e.tone}">
      <p class="panel-kicker">${r(e.label)}</p>
      <strong>${r(e.value)}</strong>
      <p>${r(e.detail)}</p>
    </article>
  `}function wa(e){return`
    <article class="route-card large tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${r(e.title)}</p>
          <p class="card-copy">${r(e.summary)}</p>
        </div>
        ${g(e.badgeLabel,e.badgeTone)}
      </div>
      <p class="mono">${r(e.detail)}</p>
      <div class="tag-row">
        ${e.tags.map(a=>g(a,"muted")).join("")}
      </div>
    </article>
  `}function Ua(e){return`
    <section class="panel cockpit-overview">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Cockpit operacional</p>
          <h2>${r(e.headline)}</h2>
          <p class="section-copy">${r(e.executiveSummary)}</p>
        </div>
        ${g(e.modeLabel,e.modeTone)}
      </div>
      <div class="hero-signals">
        ${e.signals.map(Ta).join("")}
      </div>
    </section>

    <section class="metrics-grid">
      ${e.metrics.map(Na).join("")}
    </section>

    <section class="panel priority-panel">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Direcionamento imediato</p>
          <h2>O que merece atencao primeiro</h2>
          <p class="section-copy">${r(e.nextActionDetail)}</p>
        </div>
        ${g(e.nextAction,e.nextActionTone)}
      </div>
      <div class="priority-layout">
        <article class="priority-lead tone-${e.leadAlert.tone}">
          <div class="priority-topline">
            ${g("foco da rodada",e.leadAlert.tone)}
            <strong>${r(e.leadAlert.title)}</strong>
          </div>
          <p>${r(e.leadAlert.detail)}</p>
          <p class="priority-action">${r(e.leadAlert.action)}</p>
        </article>
        <div class="priority-support">
          ${e.supportingAlerts.length?e.supportingAlerts.map(ka).join(""):`
                <article class="priority-card tone-ok">
                  <div class="priority-topline">
                    ${g("estavel","ok")}
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
                <p class="panel-kicker">${r(a.label)}</p>
                <strong>${r(a.value)}</strong>
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
              <p class="section-copy">${r(e.nextActionDetail)}</p>
            </div>
            ${g(e.panelUpdatedAt,"muted")}
          </div>
          <div class="routes-board">
            <article class="route-group tone-${e.activeRoutes.length>0?"info":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas em campo</p>
                  <strong class="route-group-count">${r(String(e.activeRoutes.length))}</strong>
                </div>
                ${g(e.activeRoutes.length>0?`${e.activeRoutes.length} rota(s)`:"sem rota",e.activeRoutes.length>0?"info":"ok")}
              </div>
              <p class="section-copy">${r(e.activeRoutes.length>0?"Entregas rodando e pedindo acompanhamento de execucao.":"Nenhuma rota ativa na leitura atual.")}</p>
              <div class="route-list">
                ${e.activeRoutes.length?e.activeRoutes.map($e).join(""):'<p class="empty-copy">Sem rota ativa.</p>'}
              </div>
            </article>
            <article class="route-group tone-${e.plannedRoutes.length>0?"warn":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas prontas</p>
                  <strong class="route-group-count">${r(String(e.plannedRoutes.length))}</strong>
                </div>
                ${g(e.plannedRoutes.length>0?`${e.plannedRoutes.length} rota(s)`:"sem rota",e.plannedRoutes.length>0?"warn":"ok")}
              </div>
              <p class="section-copy">${r(e.plannedRoutes.length>0?"Carga ja comprometida aguardando decisao para ganhar rua.":"Nenhuma rota pronta aguardando liberacao.")}</p>
              <div class="route-list">
                ${e.plannedRoutes.length?e.plannedRoutes.map($e).join(""):'<p class="empty-copy">Sem rota planejada.</p>'}
              </div>
            </article>
          </div>
          <div class="queue-grid">
            ${e.queueLanes.map(Da).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Mapa operacional</p>
              <h2>Camadas e rotas</h2>
              <p class="section-copy">${r(e.pulses.find(a=>a.label==="Rotas")?.value||"Sem resumo de rotas.")}</p>
            </div>
            ${e.mapDeposit?g(e.mapDeposit,"muted"):""}
          </div>
          <div class="micro-grid">
            ${e.mapSummaryCards.map(Fa).join("")}
          </div>
          <div class="route-list">
            ${e.mapRoutes.length?e.mapRoutes.map(wa).join(""):'<p class="empty-copy">Sem rotas mapeadas.</p>'}
          </div>
        </section>
      </div>

      <aside class="content-side">
        <section class="panel readiness-panel">
          <div class="readiness-summary">
            <div>
              <p class="panel-kicker">Confianca da leitura</p>
              <h2>${r(e.confidenceLabel)}</h2>
              <p class="section-copy">${r(e.confidenceDetail)}</p>
            </div>
            <div class="readiness-meta">
              ${g(e.readinessStatus.title,e.readinessStatus.tone)}
            </div>
          </div>
          <div class="readiness-grid">
            ${e.readinessItems.map(qa).join("")}
          </div>
          ${e.notices.map(a=>`
                <div class="notice notice-${a.tone}">
                  <strong>${r(a.label)}:</strong> ${r(a.body)}
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
            ${g(e.eventBadgeLabel,e.eventBadgeTone)}
          </div>
          <div class="event-list">
            ${e.events.length?e.events.map(Oa).join(""):'<p class="empty-copy">Nenhum evento retornado.</p>'}
          </div>
        </section>
      </aside>
    </main>
  `}function ke(e){if(!e||!e.painel)return{alerts:[{tone:"warn",title:"Sem leitura operacional suficiente",detail:"A interface ainda nao conseguiu carregar o painel principal.",action:"Validar API base e atualizar a sincronizacao."}],headline:"Aguardando dados operacionais",queueSummary:"Fila indisponivel",routeSummary:"Rotas indisponiveis",eventSummary:"Eventos indisponiveis",executiveSummary:"A operacao ainda nao entregou leitura suficiente para orientar uma decisao com seguranca.",modeLabel:"Aguardando leitura",modeTone:"warn",nextAction:"Validar conexao",nextActionDetail:"Conferir API base e executar nova sincronizacao antes de operar.",nextActionTone:"warn",confidenceLabel:"Baixa confianca",confidenceDetail:"Sem painel principal, qualquer decisao pode estar desatualizada.",confidenceTone:"danger"};const a=e.painel,t=e.mapa,n=e.eventos?.eventos||[],o=[];(e.health?.status!=="ok"||e.health?.database!=="ok")&&o.push({tone:"danger",title:"Infra com degradacao",detail:"API ou banco nao responderam como esperado nesta leitura.",action:"Conferir health e estabilizar a base antes de operar manualmente."}),e.partialErrors.length>0&&o.push({tone:"warn",title:"Visao parcial da operacao",detail:e.partialErrors.join(" | "),action:"Atualizar novamente antes de tomar decisao de despacho."});const i=a.filas.pendentesElegiveis.filter(G=>G.janelaTipo==="HARD").length;i>0&&o.push({tone:"danger",title:`${i} pedido(s) HARD aguardando despacho`,detail:"Esses pedidos merecem prioridade maxima porque a janela e mais restrita.",action:"Separar esses pedidos primeiro na avaliacao de fila."});const s=a.rotas.planejadas.length;s>0&&o.push({tone:"warn",title:`${s} rota(s) pronta(s) para iniciar`,detail:"Existe carga ja comprometida aguardando acao operacional.",action:"Confirmar se a capacidade da frota permite iniciar a proxima rota."});const $=Le(n);$>0&&o.push({tone:"info",title:`${$} ocorrencia(s) recente(s) de falha/cancelamento`,detail:"O feed operacional registrou eventos de excecao nas ultimas leituras.",action:"Olhar o bloco de ocorrencias para entender o contexto antes da proxima acao."});const v=Ga(t);v.consistent||o.push({tone:"warn",title:"Camadas da frota fora do esperado",detail:v.message,action:"Revisar leitura de rotas antes de assumir que existe uma unica primaria e uma unica secundaria."}),o.length===0&&o.push({tone:"ok",title:"Operacao em regime estavel",detail:"Nao surgiram sinais imediatos de excecao na leitura atual.",action:"Seguir monitorando fila, eventos e rotas por cadencia normal."});const A=Ma(a),I=za(a),S=Ba(a,t,v.message),l=Ha(n),E=o[0];return{alerts:o.slice(0,4),headline:A,queueSummary:I,routeSummary:S,eventSummary:l,executiveSummary:`${A}. ${I} ${S}`,modeLabel:E?.title||"Operacao estavel",modeTone:E?.tone||"ok",nextAction:E?.action||"Seguir monitorando",nextActionDetail:E?.detail||"Sem excecoes imediatas na leitura atual.",nextActionTone:E?.tone||"ok",confidenceLabel:e.partialErrors.length>0?"Confianca moderada":e.health?.status==="ok"?"Confianca alta":"Confianca reduzida",confidenceDetail:e.partialErrors.length>0?"Ha leituras parciais nesta sincronizacao; valide antes de decidir.":e.health?.status==="ok"?"Painel, eventos e mapa responderam como esperado.":"Infra ou banco sinalizaram degradacao.",confidenceTone:e.partialErrors.length>0?"warn":e.health?.status==="ok"?"ok":"danger"}}function Le(e){return e.filter(a=>{const t=String(a.eventType||"").toUpperCase();return t.includes("FALHOU")||t.includes("CANCELADO")}).length}function Ma(e){return e.filas.pendentesElegiveis.length>0?"Fila exigindo triagem ativa":e.rotas.emAndamento.length>0?"Entrega em curso com operacao ja movimentada":e.rotas.planejadas.length>0?"Operacao pronta para iniciar nova rota":"Base limpa para acompanhamento"}function za(e){return`${e.pedidosPorStatus.pendente} pendente(s), ${e.pedidosPorStatus.confirmado} confirmado(s), ${e.pedidosPorStatus.emRota} em rota.`}function Ba(e,a,t){const n=a?.rotas.filter(i=>i.camada==="PRIMARIA").length??0,o=a?.rotas.filter(i=>i.camada==="SECUNDARIA").length??0;return`${e.rotas.emAndamento.length} em andamento, ${e.rotas.planejadas.length} planejada(s), ${n} primaria(s), ${o} secundaria(s). ${t}`}function Ha(e){if(e.length===0)return"Sem ocorrencias recentes.";const a=Le(e);return a>0?`${a} excecao(oes) recente(s) em ${e.length} evento(s) lido(s).`:`${e.length} evento(s) recente(s) sem excecao imediata.`}function Ga(e){if(!e||e.rotas.length===0)return{consistent:!0,message:"Sem rotas mapeadas."};const a=e.rotas.filter(o=>o.camada==="PRIMARIA").length,t=e.rotas.filter(o=>o.camada==="SECUNDARIA").length;return a<=1&&t<=1?{consistent:!0,message:"Camadas dentro da expectativa atual."}:{consistent:!1,message:`Leitura retornou ${a} primaria(s) e ${t} secundaria(s).`}}function R(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function De(e){return typeof e!="number"||Number.isNaN(e)?"-":`${e.toFixed(1)}%`}function Y(e){if(!e)return"-";const a=new Date(e);if(Number.isNaN(a.getTime()))return e;const t=Date.now()-a.getTime(),n=Math.round(Math.abs(t)/6e4);if(n<1)return"agora";if(n<60)return t>=0?`${n} min atras`:`em ${n} min`;const o=Math.round(n/60);if(o<24)return t>=0?`${o} h atras`:`em ${o} h`;const i=Math.round(o/24);return t>=0?`${i} dia(s) atras`:`em ${i} dia(s)`}function T(e){return e?e.toLowerCase().split(/[_-]+/).filter(Boolean).map(a=>a.charAt(0).toUpperCase()+a.slice(1)).join(" "):"-"}function _a(e){return e==="health"?"Health":e==="painel"?"Painel":e==="eventos"?"Eventos":"Mapa"}function Ja(e){return e==="ok"?"ok":e==="error"?"danger":"muted"}function Va(e){return e==="danger"?"agir agora":e==="warn"?"atencao":e==="info"?"acompanhar":"estavel"}function qe(e){const a=String(e.eventType||"").toUpperCase(),t=String(e.status||"").toUpperCase();return a.includes("FALHOU")||a.includes("CANCELADO")||t.includes("ERRO")||t.includes("FALHA")?"danger":a.includes("ENTREGUE")?"ok":a.includes("ROTA")?"info":"warn"}function Xa(e){return e==="PRIMARIA"?"info":"warn"}function Oe(e){return(e.snapshot?.eventos?.eventos||[]).filter(t=>qe(t)==="danger").length}function Ka(e){const a=e.snapshot?.painel,t=a?.filas.pendentesElegiveis.filter(A=>A.janelaTipo==="HARD").length??0,n=a?.rotas.emAndamento.length??0,o=a?.rotas.planejadas.length??0,i=Oe(e),s=a?.indicadoresEntrega.taxaSucessoPercentual,$=a?.indicadoresEntrega.totalFinalizadas,v=a?.indicadoresEntrega.entregasConcluidas;return[{label:"Fila para triar",value:a?String(a.filas.pendentesElegiveis.length):"-",detail:a?`${a.pedidosPorStatus.pendente} pedido(s) pendente(s) na visao geral`:"aguardando leitura do painel",tone:a?t>0?"danger":a.filas.pendentesElegiveis.length>0?"warn":"ok":"muted"},{label:"Pedidos HARD",value:a?String(t):"-",detail:t>0?"janela critica exigindo prioridade maxima":"sem pedido critico escondido na fila",tone:a?t>0?"danger":"ok":"muted"},{label:"Rotas prontas",value:a?String(o):"-",detail:o>0?"aguardando decisao de liberar saida":"sem rota pronta esperando liberacao",tone:a?o>0?"warn":"ok":"muted"},{label:"Rotas em campo",value:a?String(n):"-",detail:n>0?"execucao ativa pedindo acompanhamento":"sem entrega em curso nesta rodada",tone:a?n>0?"info":"ok":"muted"},{label:"Excecoes recentes",value:e.snapshot?String(i):"-",detail:i>0?"falhas ou cancelamentos visiveis no feed":"feed recente sem falha aparente",tone:e.snapshot?i>=3?"danger":i>0?"warn":"ok":"muted"},{label:"Taxa de sucesso",value:a?De(s):"-",detail:a&&typeof $=="number"&&typeof v=="number"?`${v} concluida(s) em ${$} finalizada(s)`:"indicador ainda indisponivel",tone:!a||typeof s!="number"?"muted":s>=95?"ok":s>=85?"warn":"danger"}]}function Qa(e){const a=e.snapshot?.readiness;return["health","painel","eventos","mapa"].map(n=>{const o=a?.[n]||"unknown";return{label:_a(n),title:o==="ok"?"Leitura pronta":o==="error"?"Falhou nesta rodada":"Aguardando resposta",detail:o==="ok"?"Fonte pronta para sustentar o cockpit.":o==="error"?"Precisa de nova tentativa ou validacao manual.":"Ainda sem retorno desta fonte.",tone:Ja(o)}})}function Wa(e){const a=[];return e.snapshot?.partialErrors.length&&a.push({label:"Leitura parcial",body:e.snapshot.partialErrors.join(" | "),tone:"warn"}),e.sync.lastError&&a.push({label:"Ultimo erro",body:e.sync.lastError,tone:"danger"}),a}function Ya(e){return(e.snapshot?.painel?.rotas.emAndamento||[]).map(t=>({title:`R${t.rotaId}`,detail:`Entregador ${t.entregadorId}`,meta:[`${t.pendentes} pendente(s)`,`${t.emExecucao} em execucao`],badgeLabel:"em campo",badgeTone:"info",tone:"info"}))}function Za(e){return(e.snapshot?.painel?.rotas.planejadas||[]).map(t=>({title:`R${t.rotaId}`,detail:`Entregador ${t.entregadorId}`,meta:[`${t.pendentes} parada(s) pronta(s)`],badgeLabel:"aguardando saida",badgeTone:"warn",tone:"warn"}))}function et(e){const a=e.janelaTipo==="HARD";return{title:`Pedido #${e.pedidoId}`,summary:a?"Janela critica para despacho.":"Janela flexivel em triagem.",badgeLabel:a?"prioridade maxima":"fila ativa",badgeTone:a?"danger":"warn",tone:a?"danger":"warn",lines:[`${e.quantidadeGaloes} galao(oes)`,`Criado ${Y(e.criadoEm)}`,R(e.criadoEm)]}}function at(e){return{title:`Pedido #${e.pedidoId}`,summary:"Carga confirmada na secundaria aguardando liberacao de rota.",badgeLabel:`R${e.rotaId}`,badgeTone:"info",tone:"info",lines:[`Entregador ${e.entregadorId}`,`Ordem ${e.ordemNaRota}`,`${e.quantidadeGaloes} galao(oes)`]}}function tt(e){return{title:`Pedido #${e.pedidoId}`,summary:`Entrega em andamento na rota ${e.rotaId}.`,badgeLabel:T(e.statusEntrega),badgeTone:"ok",tone:"ok",lines:[`Entrega ${e.entregaId}`,`Entregador ${e.entregadorId}`,`${e.quantidadeGaloes} galao(oes)`]}}function nt(e){const a=e.snapshot?.painel?.filas,t=a?.pendentesElegiveis.filter(n=>n.janelaTipo==="HARD").length??0;return[{step:"1",title:"Triar pedidos novos",summary:t>0?`${t} pedido(s) HARD precisam abrir a decisao desta fila.`:(a?.pendentesElegiveis.length??0)>0?"Fila ativa aguardando alocacao e priorizacao.":"Entrada limpa nesta rodada.",tone:t>0?"danger":(a?.pendentesElegiveis.length??0)>0?"warn":"ok",count:a?.pendentesElegiveis.length??0,cards:(a?.pendentesElegiveis||[]).map(et),emptyMessage:"Nenhum pedido pendente elegivel."},{step:"2",title:"Liberar carga preparada",summary:(a?.confirmadosSecundaria.length??0)>0?"Pedidos ja encaixados em rota secundaria aguardando o aval operacional.":"Sem carga pronta aguardando liberacao.",tone:(a?.confirmadosSecundaria.length??0)>0?"warn":"ok",count:a?.confirmadosSecundaria.length??0,cards:(a?.confirmadosSecundaria||[]).map(at),emptyMessage:"Nenhum pedido aguardando rota secundaria."},{step:"3",title:"Acompanhar entrega em curso",summary:(a?.emRotaPrimaria.length??0)>0?"Entregas em rua que pedem monitoramento continuo.":"Sem pedido em execucao agora.",tone:(a?.emRotaPrimaria.length??0)>0?"info":"ok",count:a?.emRotaPrimaria.length??0,cards:(a?.emRotaPrimaria||[]).map(tt),emptyMessage:"Nenhum pedido em execucao agora."}]}function ot(e){return(e.snapshot?.eventos?.eventos||[]).slice(0,8).map(t=>{const n=qe(t);return{title:T(t.eventType),badgeLabel:T(t.status),badgeTone:n,subject:`${T(t.aggregateType)} ${t.aggregateId??"-"}`,detail:n==="danger"?"Excecao recente com potencial de alterar a prioridade operacional.":"Movimento recente da operacao registrado no feed.",meta:t.processedEm?`${Y(t.createdEm)} · criado ${R(t.createdEm)} · processado ${R(t.processedEm)}`:`${Y(t.createdEm)} · criado ${R(t.createdEm)}`,tone:n}})}function it(e){const a=e.snapshot?.mapa?.rotas||[],t=a.filter(i=>i.camada==="PRIMARIA").length,n=a.filter(i=>i.camada==="SECUNDARIA").length,o=a.reduce((i,s)=>i+s.paradas.length,0);return[{label:"Primarias",value:String(t),detail:"Rotas que sustentam a execucao principal.",tone:"info"},{label:"Secundarias",value:String(n),detail:"Rotas de apoio, preparacao ou contingencia.",tone:"warn"},{label:"Paradas mapeadas",value:String(o),detail:"Total de entregas representadas nesta leitura.",tone:"ok"}]}function rt(e){return(e.snapshot?.mapa?.rotas||[]).map(t=>{const n=t.paradas.filter(s=>String(s.statusEntrega).toUpperCase().includes("ENTREGUE")).length,o=Math.max(t.paradas.length-n,0),i=Xa(t.camada);return{title:`R${t.rotaId} · Entregador ${t.entregadorId}`,badgeLabel:t.camada==="PRIMARIA"?"camada primaria":"camada secundaria",badgeTone:i,summary:T(t.statusRota),detail:`${t.paradas.length} parada(s) · ${t.trajeto.length} ponto(s) no trajeto · ${n} concluida(s) · ${o} aberta(s)`,tags:t.paradas.slice(0,4).map(s=>`P${s.ordemNaRota} pedido ${s.pedidoId}`),tone:i}})}function st(e){const a=ke(e.snapshot),t=a.alerts.map(o=>({badge:Va(o.tone),tone:o.tone,title:o.title,detail:o.detail,action:o.action})),n=Oe(e);return{headline:a.headline,executiveSummary:a.executiveSummary,modeLabel:a.modeLabel,modeTone:a.modeTone,nextAction:a.nextAction,nextActionDetail:a.nextActionDetail,nextActionTone:a.nextActionTone,confidenceLabel:a.confidenceLabel,confidenceDetail:a.confidenceDetail,confidenceTone:a.confidenceTone,signals:[{label:"Modo atual",value:a.modeLabel,detail:a.executiveSummary,tone:a.modeTone},{label:"Proxima decisao",value:a.nextAction,detail:a.nextActionDetail,tone:a.nextActionTone},{label:"Confianca",value:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone}],metrics:Ka(e),leadAlert:t[0],supportingAlerts:t.slice(1),pulses:[{label:"Fila",value:a.queueSummary},{label:"Rotas",value:a.routeSummary},{label:"Ocorrencias",value:a.eventSummary}],readinessStatus:{label:a.confidenceLabel,title:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone},readinessItems:Qa(e),notices:Wa(e),panelUpdatedAt:R(e.snapshot?.painel?.atualizadoEm),activeRoutes:Ya(e),plannedRoutes:Za(e),queueLanes:nt(e),eventBadgeLabel:n>0?`${n} excecao(oes)`:"sem excecao",eventBadgeTone:n>=3?"danger":n>0?"warn":"ok",events:ot(e),mapDeposit:e.snapshot?.mapa?`Deposito ${e.snapshot.mapa.deposito.lat.toFixed(4)}, ${e.snapshot.mapa.deposito.lon.toFixed(4)}`:null,mapSummaryCards:it(e),mapRoutes:rt(e)}}function lt(e){return`
    <article class="metric-card">
      <p class="metric-label">${r(e.label)}</p>
      <p class="metric-value">${r(e.value)}</p>
      <p class="metric-detail">${r(e.detail)}</p>
    </article>
  `}function dt(e){return`
    <article class="priority-card tone-${e.tone}">
      <div class="priority-topline">
        ${g(e.badge,e.tone)}
        <strong>${r(e.title)}</strong>
      </div>
      <p>${r(e.detail)}</p>
      <p class="priority-action">${r(e.action)}</p>
    </article>
  `}function ct(e){return`
    <section class="queue-lane">
      <div class="queue-lane-header">
        <div>
          <h3>${r(e.title)}</h3>
          <p class="queue-lane-copy">${r(e.summary)}</p>
        </div>
        ${g(e.tone,e.tone)}
      </div>
      <div class="queue-list">
        ${e.cards.length>0?e.cards.map(a=>`
                    <article class="queue-card">
                      <div class="queue-card-header">
                        <strong>${r(a.title)}</strong>
                        ${g(a.badgeLabel,a.badgeTone)}
                      </div>
                      ${a.lines.map(t=>`<p class="mono">${r(t)}</p>`).join("")}
                      <p class="card-copy">${r(a.action)}</p>
                    </article>
                  `).join(""):`<p class="empty-copy">${r(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function ut(e){return`
    <article class="route-group">
      <div class="route-group-header">
        <div>
          <h3>${r(e.title)}</h3>
          <p class="section-copy">${r(e.summary)}</p>
        </div>
        ${g(String(e.routes.length),e.tone)}
      </div>
      <div class="route-list">
        ${e.routes.length>0?e.routes.map(a=>`
                    <article class="route-card">
                      <strong>${r(a.title)}</strong>
                      <p class="mono">${r(a.meta)}</p>
                    </article>
                  `).join(""):`<p class="empty-copy">${r(e.emptyMessage)}</p>`}
      </div>
    </article>
  `}function pt(e){return`
    <section class="event-bucket">
      <div class="panel-header">
        <div>
          <h3>${r(e.title)}</h3>
          <p class="section-copy">${r(e.summary)}</p>
        </div>
        ${g(String(e.cards.length),e.tone)}
      </div>
      <div class="event-list">
        ${e.cards.length>0?e.cards.map(a=>`
                    <article class="event-item">
                      <div class="event-topline">
                        <strong>${r(a.title)}</strong>
                        ${g(a.badgeLabel,a.badgeTone)}
                      </div>
                      <p class="mono">${r(a.subject)}</p>
                      <p class="event-meta">${r(a.meta)}</p>
                      <p class="card-copy">${r(a.detail)}</p>
                    </article>
                  `).join(""):`<p class="empty-copy">${r(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function mt(e){return`
    <article class="route-card large">
      <div class="queue-card-header">
        <strong>${r(e.title)}</strong>
        ${g(e.badgeLabel,e.badgeTone)}
      </div>
      <p class="mono">${r(e.summary)}</p>
      <div class="tag-row">
        ${e.tags.map(a=>g(a,"muted")).join("")}
      </div>
    </article>
  `}function gt(e){return`
    <aside class="dispatch-action-card tone-${e.tone}">
      <div class="panel-header">
        <div>
          <h2>${r(e.title)}</h2>
          <p class="section-copy">${r(e.detail)}</p>
        </div>
        ${g(e.badgeLabel,e.tone)}
      </div>
      <p class="card-copy">${r(e.supportingText)}</p>
      ${e.blocker?`<div class="notice notice-warn"><strong>Bloqueio:</strong> ${r(e.blocker)}</div>`:""}
      <button
        class="button primary"
        type="button"
        data-action="start-prepared-route"
        data-entregador-id="${r(e.entregadorId??"")}"
        ${e.enabled?"":"disabled"}
      >
        ${r(e.buttonLabel)}
      </button>
    </aside>
  `}function ft(e){return`
    <section class="dispatch-stack">
      <section class="panel dispatch-hero-panel">
        <div class="dispatch-hero-grid">
          <div>
            <p class="panel-kicker">Despacho</p>
            <h2>${r(e.headline)}</h2>
            <p class="section-copy">${r(e.summary)}</p>
            <div class="metrics-grid">
              ${e.metrics.map(lt).join("")}
            </div>
          </div>
          ${gt(e.action)}
        </div>
      </section>

      <section class="panel priority-panel">
        <div class="panel-header">
          <div>
            <h2>Prioridades de despacho</h2>
            <p class="section-copy">Leitura guiada para decisao em fila, risco, frota e ocorrencias.</p>
          </div>
          ${g("modulo ativo","info")}
        </div>
        <div class="priority-grid">
          ${e.priorities.map(dt).join("")}
        </div>
        <div class="pulse-grid">
          ${e.pulses.map(a=>`
                <article class="pulse-card">
                  <p class="label">${r(a.label)}</p>
                  <p>${r(a.value)}</p>
                </article>
              `).join("")}
        </div>
      </section>

      ${e.notices.length>0?`
            <section class="dispatch-notice-stack">
              ${e.notices.map(a=>`
                    <div class="notice notice-${a.tone}">
                      <strong>${r(a.label)}:</strong> ${r(a.body)}
                    </div>
                  `).join("")}
            </section>
          `:""}

      <section class="panel">
        <div class="panel-header">
          <div>
            <h2>Fila operacional</h2>
            <p class="section-copy">A fila e lida pelo estagio de decisao, nao pela resposta crua do backend.</p>
          </div>
          ${g(String(e.queueLanes.reduce((a,t)=>a+t.cards.length,0)),"info")}
        </div>
        <div class="queue-grid">
          ${e.queueLanes.map(ct).join("")}
        </div>
      </section>

      <main class="dispatch-grid">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>Camadas de frota</h2>
              <p class="section-copy">Separacao clara entre primaria, secundaria e qualquer incoerencia do read model.</p>
            </div>
            ${g(String(e.layerWarnings.length),e.layerWarnings.length>0?"warn":"ok")}
          </div>
          ${e.layerWarnings.length>0?`<div class="notice notice-warn"><strong>Anomalias de camada:</strong> ${r(e.layerWarnings.join(" | "))}</div>`:'<div class="notice notice-ok"><strong>Modelo consistente:</strong> primaria e secundaria legiveis nesta leitura.</div>'}
          <div class="dispatch-layer-grid">
            ${e.layerCards.map(ut).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>Feed de ocorrencias</h2>
              <p class="section-copy">O feed separa o que pede acao imediata do que e apenas movimento normal da operacao.</p>
            </div>
            ${g(String(e.eventBuckets.reduce((a,t)=>a+t.cards.length,0)),"info")}
          </div>
          <div class="dispatch-event-grid">
            ${e.eventBuckets.map(pt).join("")}
          </div>
        </section>

        <section class="panel dispatch-routes-panel">
          <div class="panel-header">
            <div>
              <h2>Rotas e mapa operacional</h2>
              <p class="section-copy">${r(e.routeSummary)}</p>
            </div>
            ${e.mapDeposit?g(e.mapDeposit,"muted"):g("sem mapa","warn")}
          </div>
          <div class="route-list">
            ${e.routeCards.length>0?e.routeCards.map(mt).join(""):'<p class="empty-copy">Nenhuma rota retornada pelo mapa operacional.</p>'}
          </div>
        </section>
      </main>
    </section>
  `}function be(e){return e.tone}function ht(e){return e==="danger"?"agir agora":e==="warn"?"atencao":e==="info"?"acompanhar":"estavel"}function w(e){const a=String(e.eventType||"").toUpperCase();return a.includes("FALHOU")||a.includes("CANCELADO")?"danger":a.includes("ENTREGUE")?"ok":a.includes("ROTA")?"info":"warn"}function vt(e,a){const t=String(e||"").toUpperCase();if(t.includes("PRIMARIA"))return"PRIMARIA";if(t.includes("SECUNDARIA"))return"SECUNDARIA";const n=String(a||"").toUpperCase();return n==="EM_ANDAMENTO"?"PRIMARIA":n==="PLANEJADA"?"SECUNDARIA":"DESCONHECIDA"}function $t(e){switch(String(e||"").toUpperCase()){case"EM_EXECUCAO":return"emExecucao";case"ENTREGUE":case"CONCLUIDA":case"CANCELADA":return"concluidas";default:return"pendentes"}}function bt(e){const a=new Map;for(const t of e?.mapa?.rotas??[]){const n=At(t);a.set(t.rotaId,n)}for(const t of e?.painel?.rotas.emAndamento??[]){const n=a.get(t.rotaId);a.set(t.rotaId,{routeId:t.rotaId,entregadorId:t.entregadorId,camada:"PRIMARIA",statusRota:n?.statusRota??"EM_ANDAMENTO",paradas:n?.paradas??t.pendentes+t.emExecucao,pontosTrajeto:n?.pontosTrajeto??0,pendentes:t.pendentes,emExecucao:t.emExecucao,concluidas:n?.concluidas??0,quantidadeGaloes:n?.quantidadeGaloes??0,tags:n?.tags??[]})}for(const t of e?.painel?.rotas.planejadas??[]){const n=a.get(t.rotaId);a.set(t.rotaId,{routeId:t.rotaId,entregadorId:t.entregadorId,camada:"SECUNDARIA",statusRota:n?.statusRota??"PLANEJADA",paradas:n?.paradas??t.pendentes,pontosTrajeto:n?.pontosTrajeto??0,pendentes:t.pendentes,emExecucao:n?.emExecucao??0,concluidas:n?.concluidas??0,quantidadeGaloes:n?.quantidadeGaloes??0,tags:n?.tags??[]})}return[...a.values()].sort((t,n)=>Ae(t.camada)-Ae(n.camada)||t.routeId-n.routeId)}function At(e){const a=e.paradas.reduce((t,n)=>(t[$t(n.statusEntrega)]+=1,t.quantidadeGaloes+=n.quantidadeGaloes,t),{pendentes:0,emExecucao:0,concluidas:0,quantidadeGaloes:0});return{routeId:e.rotaId,entregadorId:e.entregadorId,camada:vt(e.camada,e.statusRota),statusRota:e.statusRota,paradas:e.paradas.length,pontosTrajeto:e.trajeto.length,pendentes:a.pendentes,emExecucao:a.emExecucao,concluidas:a.concluidas,quantidadeGaloes:a.quantidadeGaloes,tags:e.paradas.slice().sort((t,n)=>t.ordemNaRota-n.ordemNaRota).slice(0,5).map(t=>`P${t.ordemNaRota} · pedido ${t.pedidoId}`)}}function Ae(e){return e==="PRIMARIA"?0:e==="SECUNDARIA"?1:2}function It(e){const a=e.filter(i=>i.camada==="PRIMARIA").length,t=e.filter(i=>i.camada==="SECUNDARIA").length,n=e.filter(i=>i.camada==="DESCONHECIDA").length,o=[];return a>1&&o.push(`Foram detectadas ${a} rotas na camada PRIMARIA.`),t>1&&o.push(`Foram detectadas ${t} rotas na camada SECUNDARIA.`),n>0&&o.push(`${n} rota(s) vieram sem camada reconhecivel.`),o}function Et(e){const a=String(e.janelaTipo||"ASAP").toUpperCase();return{title:`Pedido #${e.pedidoId}`,badgeLabel:a,badgeTone:a==="HARD"?"danger":a==="ASAP"?"warn":"info",lines:[`${e.quantidadeGaloes} galao(oes)`,`Criado em ${R(e.criadoEm)}`],action:a==="HARD"?"Priorizar este encaixe antes da proxima rodada.":"Avaliar com a proxima disponibilidade da frota."}}function yt(e){return{title:`Pedido #${e.pedidoId}`,badgeLabel:`R${e.rotaId}`,badgeTone:"info",lines:[`Entregador ${e.entregadorId} · ordem ${e.ordemNaRota}`,`${e.quantidadeGaloes} galao(oes) ja comprometidos`],action:"Checar contexto da secundaria antes de girar a rota."}}function St(e){return{title:`Pedido #${e.pedidoId}`,badgeLabel:e.statusEntrega,badgeTone:e.statusEntrega==="EM_EXECUCAO"?"ok":"info",lines:[`Entrega ${e.entregaId} · rota ${e.rotaId}`,`Entregador ${e.entregadorId} · ${e.quantidadeGaloes} galao(oes)`],action:"Monitorar excecoes e liberar espaco para a proxima onda."}}function Ct(e){const a=Object.entries(e.payload??{}).filter(([,t])=>{const n=typeof t;return n==="string"||n==="number"||n==="boolean"}).slice(0,2).map(([t,n])=>`${t}: ${String(n)}`);return a.length>0?a.join(" · "):"Sem detalhe resumivel."}function Ie(e){const a=w(e);return{title:e.eventType,badgeLabel:a==="danger"?"agir":e.status,badgeTone:a,subject:`${e.aggregateType} ${e.aggregateId??"-"}`,meta:e.processedEm?`criado ${R(e.createdEm)} · processado ${R(e.processedEm)}`:`criado ${R(e.createdEm)}`,detail:Ct(e)}}function Rt(e){const a=e.snapshot,t=a?.painel,n=ke(a),o=bt(a),i=It(o),s=(a?.eventos?.eventos??[]).filter(l=>w(l)==="danger").length,$=t?.filas.pendentesElegiveis.filter(l=>String(l.janelaTipo).toUpperCase()==="HARD").length??0,v=o.filter(l=>l.camada==="SECUNDARIA"),A=v[0]??null,I=[];t||I.push("Painel operacional indisponivel."),a?.mapa||I.push("Mapa operacional indisponivel."),i.length>0&&I.push(i[0]),v.length===0&&I.push("Nenhuma rota secundaria pronta para iniciar."),v.length>1&&I.push("Mais de uma secundaria apareceu na mesma leitura.");const S=I.length===0&&A!==null;return{headline:t?$>0?"Fila pressionando a triagem":v.length>0?"Saida pronta para decisao":s>0?"Excecoes pedindo leitura cuidadosa":"Despacho sob controle no recorte atual":"Aguardando contexto seguro de despacho",summary:t?`${t.filas.pendentesElegiveis.length} entrada(s) na triagem · ${t.rotas.planejadas.length} rota(s) planejada(s) · ${s} excecao(oes) recente(s).`:"Sem painel principal nao ha contexto suficiente para girar a frota com seguranca.",metrics:[{label:"Pendentes criticos",value:String($),detail:"Pedidos HARD exigindo resposta curta.",tone:$>0?"danger":"ok"},{label:"Fila de triagem",value:String(t?.filas.pendentesElegiveis.length??0),detail:"Entradas novas aguardando encaixe.",tone:(t?.filas.pendentesElegiveis.length??0)>0?"warn":"ok"},{label:"Secundaria pronta",value:String(t?.rotas.planejadas.length??0),detail:"Carga preparada para virar saida.",tone:(t?.rotas.planejadas.length??0)>0?"info":"muted"},{label:"Primaria em curso",value:String(t?.rotas.emAndamento.length??0),detail:"Rotas que ja sustentam a operacao.",tone:(t?.rotas.emAndamento.length??0)>0?"ok":"muted"},{label:"Excecoes recentes",value:String(s),detail:"Falhas e cancelamentos no feed.",tone:s>0?"danger":"ok"},{label:"Taxa de sucesso",value:De(t?.indicadoresEntrega.taxaSucessoPercentual),detail:"Finalizacao acumulada do read model.",tone:(t?.indicadoresEntrega.taxaSucessoPercentual??0)>=90?"ok":"info"}],priorities:n.alerts.map(l=>({badge:ht(be(l)),tone:be(l),title:l.title,detail:l.detail,action:l.action})),pulses:[{label:"Leitura da fila",value:n.queueSummary},{label:"Leitura das rotas",value:n.routeSummary},{label:"Leitura das ocorrencias",value:n.eventSummary}],queueLanes:[{title:"Triagem imediata",tone:$>0?"danger":(t?.filas.pendentesElegiveis.length??0)>0?"warn":"ok",summary:(t?.filas.pendentesElegiveis.length??0)>0?`${t?.filas.pendentesElegiveis.length??0} pedido(s) aguardando avaliacao.`:"Sem nova entrada pressionando a fila.",cards:(t?.filas.pendentesElegiveis??[]).slice().sort((l,E)=>l.criadoEm.localeCompare(E.criadoEm)).map(Et),emptyMessage:"Nenhum pedido pendente elegivel neste momento."},{title:"Preparar saida",tone:(t?.filas.confirmadosSecundaria.length??0)>0?"info":(t?.rotas.planejadas.length??0)>0?"warn":"muted",summary:(t?.filas.confirmadosSecundaria.length??0)>0?`${t?.filas.confirmadosSecundaria.length??0} pedido(s) comprometidos na secundaria.`:(t?.rotas.planejadas.length??0)>0?"Existe rota pronta, mas sem detalhamento dos pedidos nesta leitura.":"Sem carga montada para uma nova saida.",cards:(t?.filas.confirmadosSecundaria??[]).map(yt),emptyMessage:(t?.rotas.planejadas.length??0)>0?"A secundaria existe, mas o read model nao detalhou os pedidos desta rota.":"Nenhum pedido confirmado aguardando saida."},{title:"Monitorar execucao",tone:(t?.filas.emRotaPrimaria.length??0)>0?"ok":"muted",summary:(t?.filas.emRotaPrimaria.length??0)>0?`${t?.filas.emRotaPrimaria.length??0} entrega(s) em circulacao na primaria.`:"Sem entrega ativa na primaria agora.",cards:(t?.filas.emRotaPrimaria??[]).map(St),emptyMessage:"Nenhum pedido em execucao agora."}],layerCards:[{title:"Camada primaria",tone:o.some(l=>l.camada==="PRIMARIA")?"ok":"muted",summary:"Sustenta a execucao que ja saiu para a rua.",routes:o.filter(l=>l.camada==="PRIMARIA").map(l=>({title:`R${l.routeId} · Entregador ${l.entregadorId}`,meta:`${l.pendentes} pendente(s) · ${l.emExecucao} em execucao · ${l.concluidas} concluida(s)`})),emptyMessage:"Nenhuma rota primaria encontrada."},{title:"Camada secundaria",tone:o.some(l=>l.camada==="SECUNDARIA")?"info":"muted",summary:"Reserva operacional pronta para a proxima decisao.",routes:o.filter(l=>l.camada==="SECUNDARIA").map(l=>({title:`R${l.routeId} · Entregador ${l.entregadorId}`,meta:`${l.pendentes} pendente(s) · ${l.paradas} parada(s) previstas`})),emptyMessage:"Nenhuma rota secundaria pronta neste recorte."},{title:"Leitura inconsistente",tone:o.some(l=>l.camada==="DESCONHECIDA")?"warn":"muted",summary:"Qualquer rota sem camada clara pede dupla checagem antes de agir.",routes:o.filter(l=>l.camada==="DESCONHECIDA").map(l=>({title:`R${l.routeId} · Entregador ${l.entregadorId}`,meta:`${l.statusRota} · ${l.paradas} parada(s) lida(s)`})),emptyMessage:"Todas as rotas vieram com camada reconhecivel."}],layerWarnings:i,eventBuckets:[{title:"Excecoes para agir",tone:s>0?"danger":"ok",summary:s>0?`${s} evento(s) merecem triagem imediata.`:"Sem falha ou cancelamento na janela recente.",cards:(a?.eventos?.eventos??[]).filter(l=>w(l)==="danger").slice(0,5).map(Ie),emptyMessage:"Nenhuma excecao recente."},{title:"Fluxo operacional",tone:"info",summary:"Rotas iniciadas, entregas concluidas e movimentos normais da operacao.",cards:(a?.eventos?.eventos??[]).filter(l=>{const E=w(l);return E==="info"||E==="ok"}).slice(0,5).map(Ie),emptyMessage:"Nenhum movimento recente de rota ou entrega."}],routeCards:o.map(l=>({title:`R${l.routeId} · Entregador ${l.entregadorId}`,badgeLabel:l.camada,badgeTone:l.camada==="PRIMARIA"?"ok":l.camada==="SECUNDARIA"?"info":"warn",summary:`${l.statusRota} · ${l.paradas} parada(s) · ${l.pontosTrajeto} ponto(s) no trajeto · ${l.quantidadeGaloes} galao(oes)`,tags:l.tags.length>0?l.tags:["sem paradas detalhadas"]})),routeSummary:o.length>0?`${o.filter(l=>l.camada==="PRIMARIA").length} primaria(s), ${o.filter(l=>l.camada==="SECUNDARIA").length} secundaria(s) e ${o.reduce((l,E)=>l+E.paradas,0)} parada(s) mapeada(s).`:"Sem rotas materializadas no mapa operacional.",mapDeposit:a?.mapa?`Deposito ${a.mapa.deposito.lat.toFixed(4)}, ${a.mapa.deposito.lon.toFixed(4)}`:null,action:{title:S?"Acao recomendada agora":"Acao operacional protegida",tone:e.despacho.routeStart.status==="loading"?"warn":S?"info":"warn",badgeLabel:e.despacho.routeStart.status==="loading"?"executando":S?"liberado":"bloqueado",detail:S&&A?`Iniciar R${A.routeId} do entregador ${A.entregadorId} para transformar a secundaria em frota ativa.`:A?`Existe candidata R${A.routeId}, mas o contexto ainda nao esta seguro para girar.`:"Sem candidata confiavel para o proximo giro de frota.",supportingText:S?"O gatilho usa o endpoint operacional real de inicio de rota pronta.":"A saida manual fica bloqueada quando a leitura esta parcial ou quando as camadas nao estao consistentes.",buttonLabel:e.despacho.routeStart.status==="loading"?"Iniciando rota...":S&&A?`Iniciar R${A.routeId}`:"Aguardando contexto seguro",enabled:S,entregadorId:A?.entregadorId??null,blocker:S?null:I[0]??null},notices:[...a?.partialErrors.length?[{label:"Leitura parcial",body:a.partialErrors.join(" | "),tone:"warn"}]:[],...e.sync.lastError?[{label:"Ultimo erro de sincronizacao",body:e.sync.lastError,tone:"danger"}]:[],...e.despacho.lastRouteStart?[{label:e.despacho.lastRouteStart.title,body:e.despacho.lastRouteStart.detail,tone:e.despacho.lastRouteStart.tone}]:[]]}}function xt(e){return`
    <section class="panel module-nav">
      <div class="panel-header">
        <div>
          <h2>Fluxos da operacao</h2>
          <p class="section-copy">Cada modulo organiza a mesma operacao sob a otica do papel que esta trabalhando agora.</p>
        </div>
        ${g(B(e.activeModule).label,"info")}
      </div>
      <div class="module-tab-list">
        ${oe.map(a=>`
              <button
                class="module-tab ${a.id===e.activeModule?"is-active":""}"
                type="button"
                data-action="navigate"
                data-module-id="${r(a.id)}"
              >
                <div class="module-tab-topline">
                  <strong>${r(a.label)}</strong>
                  ${g(a.status==="active"?"ativo":"planejado",a.status==="active"?"ok":"muted")}
                </div>
                <p>${r(a.description)}</p>
              </button>
            `).join("")}
      </div>
    </section>
  `}function Pt(e){const a=B(e.activeModule);return`
    <section class="panel">
      <div class="panel-header">
        <div>
          <h2>${r(a.label)}</h2>
          <p class="section-copy">${r(a.description)}</p>
        </div>
        ${g("em preparacao","warn")}
      </div>
      <div class="notice notice-warn">
        <strong>Proxima etapa:</strong> este fluxo ja esta previsto na shell modular e entra nos PRs seguintes.
      </div>
    </section>
  `}function jt(e){return e.activeModule==="atendimento"?ja(e):e.activeModule==="despacho"?ft(Rt(e)):e.activeModule!=="cockpit"?Pt(e):Ua(st(e))}function Fe(e,a){const t=B(a.activeModule),n=a.snapshot?.partialErrors.length??0;e.innerHTML=`
    <div class="app-shell">
      <header class="hero">
        <div class="hero-copy-block">
          <p class="eyebrow">Agua Viva</p>
          <h1>Operacao web por papel</h1>
          <p class="hero-copy">${r(t.description)}</p>
          <div class="hero-meta">
            ${g("dados reais","info")}
            ${g(a.connection.autoRefresh?"auto-refresh ligado":"auto-refresh desligado",a.connection.autoRefresh?"ok":"muted")}
            ${g(a.snapshot?.fetchedAt?R(a.snapshot.fetchedAt):"sem sincronizacao","muted")}
          </div>
        </div>
        <div class="hero-signals">
          <article class="signal-card">
            <p class="label">Modulo ativo</p>
            <strong>${r(t.label)}</strong>
            <p>${r(t.description)}</p>
          </article>
          <article class="signal-card">
            <p class="label">Sincronizacao</p>
            <strong>${r(a.sync.status)}</strong>
            <p>${r(a.sync.lastError||"Leitura operacional sem erro nesta rodada.")}</p>
          </article>
          <article class="signal-card">
            <p class="label">Leitura parcial</p>
            <strong>${r(String(n))}</strong>
            <p>${n>0?"Existe degradacao em pelo menos um read model.":"Todos os read models responderam nesta rodada."}</p>
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
              <input id="api-base-input" type="text" value="${r(a.connection.apiBaseDraft)}" spellcheck="false" />
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

      ${xt(a)}
      ${jt(a)}
    </div>
  `}const we="agua-viva.operacao-web.api-base",Ue="agua-viva.operacao-web.auto-refresh",Me="agua-viva.operacao-web.atendimento-state",Tt="http://localhost:8082";function re(e){try{return window.localStorage.getItem(e)}catch{return null}}function se(e,a){try{window.localStorage.setItem(e,a)}catch{}}function Nt(e){const a=re(e);if(!a)return null;try{return JSON.parse(a)}catch{return null}}function kt(){return re(we)||Tt}function Lt(e){se(we,e)}function Dt(){return re(Ue)!=="0"}function qt(e){se(Ue,e?"1":"0")}function Ot(){return Nt(Me)}function Ft(e){se(Me,JSON.stringify(e))}const wt=15e3,Ut=1,ze=document.querySelector("#app");if(!ze)throw new Error("Elemento #app nao encontrado.");const le=ze,Ee=kt(),Mt=Bt()??Ut,zt={activeModule:W(window.location.hash),connection:{apiBase:Ee,apiBaseDraft:Ee,autoRefresh:Dt()},sync:{status:"idle",lastError:null},snapshot:null,atendimento:Je(Ot()),despacho:{routeStart:{status:"idle",lastError:null},lastRouteStart:null},entregador:{entregadorId:Mt,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}},k=Aa(zt),Be=ba(e=>{k.setActiveModule(e)});let L;const de=$a({intervalMs:wt,onTick:()=>{L.refreshSnapshot()}});L=ha({root:le,router:Be,store:k,persistApiBase:Lt,persistAutoRefresh:qt,persistAtendimentoState:Ft});k.subscribe(e=>{Fe(le,e),de.sync(e.connection.autoRefresh)});L.bind();Fe(le,k.getState());Be.start();de.sync(k.getState().connection.autoRefresh);L.refreshSnapshot();window.addEventListener("beforeunload",()=>{L.dispose(),de.stop()});function Bt(){try{const e=new URL(window.location.href),a=Number(e.searchParams.get("entregadorId"));return Number.isInteger(a)&&a>0?a:null}catch{return null}}
