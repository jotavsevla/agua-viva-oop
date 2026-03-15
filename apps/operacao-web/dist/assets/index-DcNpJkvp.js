(function(){const a=document.createElement("link").relList;if(a&&a.supports&&a.supports("modulepreload"))return;for(const o of document.querySelectorAll('link[rel="modulepreload"]'))t(o);new MutationObserver(o=>{for(const i of o)if(i.type==="childList")for(const r of i.addedNodes)r.tagName==="LINK"&&r.rel==="modulepreload"&&t(r)}).observe(document,{childList:!0,subtree:!0});function n(o){const i={};return o.integrity&&(i.integrity=o.integrity),o.referrerPolicy&&(i.referrerPolicy=o.referrerPolicy),o.crossOrigin==="use-credentials"?i.credentials="include":o.crossOrigin==="anonymous"?i.credentials="omit":i.credentials="same-origin",i}function t(o){if(o.ep)return;o.ep=!0;const i=n(o);fetch(o.href,i)}})();const be=["MANUAL","WHATSAPP","BINA_FIXO","TELEFONIA_FIXO"],$e=["NAO_INFORMADO","DINHEIRO","PIX","CARTAO","VALE"],Ae=["ASAP","HARD","FLEXIVEL"],qe=new Set(["ENTREGUE","CANCELADO"]),K=new Set(["WHATSAPP","BINA_FIXO","TELEFONIA_FIXO"]);function l(e){return String(e??"").trim()}function W(e){return l(e).toUpperCase()}function ce(e,a){const n=Number(e);if(!Number.isInteger(n)||n<=0)throw new Error(`${a} invalido`);return n}function ue(e,a){const n=l(e).replace(",",".");if(!n)throw new Error(`${a} invalida`);const t=Number(n);if(!Number.isFinite(t))throw new Error(`${a} invalida`);return t}function Z(e){const a=W(e);if(!a)return"";if(!be.includes(a))throw new Error("origemCanal invalido");return a}function ee(e){const a=W(e);if(!a)return"";if(!$e.includes(a))throw new Error("metodoPagamento invalido");return a}function ae(e){const a=W(e);if(!a)return"";if(a==="FLEX")return"FLEXIVEL";if(!Ae.includes(a))throw new Error("janelaTipo invalido");return a}function Ie(){return{telefone:"",quantidadeGaloes:"1",atendenteId:"1",origemCanal:"MANUAL",sourceEventId:"",manualRequestId:F(),externalCallId:"",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",latitude:"",longitude:""}}function Fe(e){const a=T(e?.draft),n=Me(e?.sessionCases),t=e?.activeCaseId??null;return{draft:a,lookupPhone:l(e?.lookupPhone),lookupPedidoId:l(e?.lookupPedidoId),sessionCases:n,activeCaseId:n.some(o=>o.pedidoId===t)?t:null}}function ze(e){return{...Fe(e),submitting:!1,syncingCaseId:null,lastError:null,lastSuccess:null}}function T(e){const a=Ie(),n={...a,...e,telefone:l(e?.telefone),quantidadeGaloes:l(e?.quantidadeGaloes)||a.quantidadeGaloes,atendenteId:l(e?.atendenteId)||a.atendenteId,origemCanal:Z(l(e?.origemCanal))||a.origemCanal,sourceEventId:l(e?.sourceEventId),manualRequestId:l(e?.manualRequestId),externalCallId:l(e?.externalCallId),metodoPagamento:ee(l(e?.metodoPagamento))||a.metodoPagamento,janelaTipo:ae(l(e?.janelaTipo))||a.janelaTipo,janelaInicio:l(e?.janelaInicio),janelaFim:l(e?.janelaFim),nomeCliente:l(e?.nomeCliente),endereco:l(e?.endereco),latitude:l(e?.latitude),longitude:l(e?.longitude)};return n.origemCanal==="MANUAL"&&!n.manualRequestId&&(n.manualRequestId=F()),n}function Me(e){return Array.isArray(e)?e.filter(a=>!!(a&&Number.isInteger(a.pedidoId))).map(a=>({pedidoId:a.pedidoId,clienteId:Number.isInteger(a.clienteId)?a.clienteId:null,telefone:l(a.telefone),telefoneNormalizado:z(a.telefoneNormalizado||a.telefone),quantidadeGaloes:Number.isInteger(a.quantidadeGaloes)?a.quantidadeGaloes:0,atendenteId:Number.isInteger(a.atendenteId)?a.atendenteId:0,origemCanal:Z(a.origemCanal)||"MANUAL",metodoPagamento:ee(a.metodoPagamento)||"NAO_INFORMADO",janelaTipo:ae(a.janelaTipo)||"ASAP",janelaInicio:l(a.janelaInicio),janelaFim:l(a.janelaFim),nomeCliente:l(a.nomeCliente),endereco:l(a.endereco),requestKey:l(a.requestKey)||null,clienteCriado:!!a.clienteCriado,idempotente:!!a.idempotente,statusAtual:l(a.statusAtual)||null,timeline:a.timeline||null,execucao:a.execucao||null,saldo:a.saldo||null,extrato:a.extrato||null,financeStatus:Be(a.financeStatus),notes:Array.isArray(a.notes)?a.notes.map(n=>l(n)).filter(Boolean):[],error:l(a.error)||null,createdAt:l(a.createdAt)||new Date().toISOString(),updatedAt:l(a.updatedAt)||l(a.createdAt)||new Date().toISOString(),lastSyncAt:l(a.lastSyncAt)||null})).sort(X):[]}function Be(e){return e==="ok"||e==="unavailable"||e==="error"?e:"idle"}function F(){const e=new Date().toISOString().replace(/[-:.TZ]/g,"").slice(0,14),a=Math.random().toString(36).slice(2,8);return`manual-ui-${e}-${a}`}function pe(e){const a=T({telefone:l(e.get("telefone")),quantidadeGaloes:l(e.get("quantidadeGaloes")),atendenteId:l(e.get("atendenteId")),origemCanal:Z(l(e.get("origemCanal"))),sourceEventId:l(e.get("sourceEventId")),manualRequestId:l(e.get("manualRequestId")),externalCallId:l(e.get("externalCallId")),metodoPagamento:ee(l(e.get("metodoPagamento"))),janelaTipo:ae(l(e.get("janelaTipo"))),janelaInicio:l(e.get("janelaInicio")),janelaFim:l(e.get("janelaFim")),nomeCliente:l(e.get("nomeCliente")),endereco:l(e.get("endereco")),latitude:l(e.get("latitude")),longitude:l(e.get("longitude"))});return a.origemCanal==="MANUAL"&&!a.manualRequestId&&(a.manualRequestId=F()),a}function ne(e){const a=T(e),n={telefone:l(a.telefone),quantidadeGaloes:ce(a.quantidadeGaloes,"quantidadeGaloes"),atendenteId:ce(a.atendenteId,"atendenteId")};if(!n.telefone)throw new Error("telefone obrigatorio");a.origemCanal&&(n.origemCanal=a.origemCanal),a.metodoPagamento&&(n.metodoPagamento=a.metodoPagamento),a.janelaTipo&&(n.janelaTipo=a.janelaTipo),a.sourceEventId&&(n.sourceEventId=a.sourceEventId),a.manualRequestId&&(n.manualRequestId=a.manualRequestId),a.externalCallId&&(n.externalCallId=a.externalCallId),a.janelaInicio&&(n.janelaInicio=a.janelaInicio),a.janelaFim&&(n.janelaFim=a.janelaFim),a.nomeCliente&&(n.nomeCliente=a.nomeCliente),a.endereco&&(n.endereco=a.endereco);const t=!!a.latitude,o=!!a.longitude;if(t!==o)throw new Error("latitude e longitude devem ser informadas juntas");return t&&o&&(n.latitude=ue(a.latitude,"latitude"),n.longitude=ue(a.longitude,"longitude")),Ue(n),He(n),{payload:n,idempotencyKey:_e(n)}}function Ue(e){const a=e.origemCanal||"";if(a==="MANUAL"&&e.sourceEventId)throw new Error("sourceEventId nao pode ser usado com origemCanal=MANUAL");if(K.has(a)&&!e.sourceEventId)throw new Error(`sourceEventId obrigatorio para origemCanal=${a}`);if(K.has(a)&&e.manualRequestId)throw new Error(`manualRequestId so pode ser usado com origemCanal=MANUAL (recebido: ${a})`);if(!a&&e.sourceEventId&&e.manualRequestId)throw new Error("manualRequestId nao pode ser combinado com sourceEventId quando origemCanal estiver vazio");if(e.sourceEventId&&e.externalCallId&&e.sourceEventId!==e.externalCallId)throw new Error("sourceEventId diverge de externalCallId");if(e.manualRequestId&&e.externalCallId&&e.manualRequestId!==e.externalCallId)throw new Error("manualRequestId diverge de externalCallId")}function He(e){if(e.janelaTipo==="HARD"){if(!e.janelaInicio||!e.janelaFim)throw new Error("janelaTipo=HARD exige janelaInicio e janelaFim");return}if(e.janelaInicio||e.janelaFim)throw new Error("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD")}function _e(e){return e.origemCanal==="MANUAL"?e.manualRequestId||e.externalCallId||null:K.has(e.origemCanal||"")?e.sourceEventId||e.externalCallId||null:e.sourceEventId||e.manualRequestId||e.externalCallId||null}function Ee(e,a){const n=[];try{ne(e)}catch(o){n.push(o instanceof Error?o.message:"payload invalido")}const t=z(e.telefone);if(t){const o=a.find(i=>i.telefoneNormalizado===t&&!Xe(i));o&&n.push(`Ja existe pedido em acompanhamento na sessao para este telefone (#${o.pedidoId}, ${o.statusAtual||"sem status"}).`)}return M(n)}function Ge(e){const a=[];return e.metodoPagamento==="VALE"&&a.push("Checkout em vale depende de saldo suficiente no cliente e pode ser bloqueado pelo backend."),e.origemCanal==="MANUAL"&&!e.nomeCliente&&a.push("Sem nome do cliente, o pedido entra, mas o atendimento perde contexto para o despacho."),e.janelaTipo==="HARD"&&a.push("Pedidos HARD devem entrar com horario fechado para evitar retrabalho no despacho."),M(a)}function z(e){return String(e??"").replace(/\D/g,"")}function ye(e){const a=z(e);return a.length===11?`(${a.slice(0,2)}) ${a.slice(2,7)}-${a.slice(7)}`:a.length===10?`(${a.slice(0,2)}) ${a.slice(2,6)}-${a.slice(6)}`:l(e)}function Ve(e,a){const n=T(e),t=new Date().toISOString(),{payload:o,idempotencyKey:i}=ne(n);return{pedidoId:a.pedidoId,clienteId:a.clienteId,telefone:o.telefone,telefoneNormalizado:a.telefoneNormalizado,quantidadeGaloes:o.quantidadeGaloes,atendenteId:o.atendenteId,origemCanal:o.origemCanal||"MANUAL",metodoPagamento:o.metodoPagamento||"NAO_INFORMADO",janelaTipo:o.janelaTipo||"ASAP",janelaInicio:o.janelaInicio||"",janelaFim:o.janelaFim||"",nomeCliente:o.nomeCliente||"",endereco:o.endereco||"",requestKey:i,clienteCriado:a.clienteCriado,idempotente:a.idempotente,statusAtual:"PENDENTE",timeline:null,execucao:null,saldo:null,extrato:null,financeStatus:"idle",notes:[],error:null,createdAt:t,updatedAt:t,lastSyncAt:null}}function me(e,a){const n=a.timeline===void 0?e.timeline:a.timeline,t=a.execucao===void 0?e.execucao:a.execucao;return{...e,timeline:n,execucao:t,saldo:a.saldo===void 0?e.saldo:a.saldo,extrato:a.extrato===void 0?e.extrato:a.extrato,financeStatus:a.financeStatus||e.financeStatus,statusAtual:n?.statusAtual||t?.statusPedido||e.statusAtual,error:a.error===void 0?e.error:a.error,notes:a.notes?M([...e.notes,...a.notes]):e.notes,updatedAt:new Date().toISOString(),lastSyncAt:new Date().toISOString()}}function Je(e){const a=T(e);return{...Ie(),quantidadeGaloes:a.quantidadeGaloes,atendenteId:a.atendenteId,origemCanal:"MANUAL",metodoPagamento:a.metodoPagamento,janelaTipo:a.janelaTipo,janelaInicio:a.janelaTipo==="HARD"?a.janelaInicio:"",janelaFim:a.janelaTipo==="HARD"?a.janelaFim:"",manualRequestId:F()}}function V(e,a){const n=e.findIndex(o=>o.pedidoId===a.pedidoId);if(n===-1)return[...e,a].sort(X);const t=[...e];return t[n]={...t[n],...a,notes:M([...t[n].notes||[],...a.notes||[]])},t.sort(X)}function Ke(e){return e.sessionCases.find(a=>a.pedidoId===e.activeCaseId)||null}function Se(e,a){const n=z(a);return n?e.sessionCases.filter(t=>t.telefoneNormalizado.includes(n)):e.sessionCases}function Xe(e){return qe.has(String(e.statusAtual||"").toUpperCase())}function Ce(e){return e.janelaTipo==="HARD"?e.janelaInicio&&e.janelaFim?`HARD ${e.janelaInicio}-${e.janelaFim}`:"HARD incompleta":e.janelaTipo==="FLEXIVEL"?"Flexivel":"ASAP"}function Qe(e){if(!e)return{tone:"muted",stage:"Sem pedido em foco",detail:"Crie um novo pedido ou selecione um atendimento da sessao para montar o handoff.",action:"Nada para repassar ao despacho por enquanto."};const a=String(e.statusAtual||"").toUpperCase(),n=String(e.execucao?.camada||"").toUpperCase();return a==="CANCELADO"?{tone:"danger",stage:"Atendimento interrompido",detail:"O pedido foi cancelado e nao deve seguir para a operacao sem nova triagem.",action:"Registrar motivo com clareza e orientar novo contato se necessario."}:a==="ENTREGUE"?{tone:"ok",stage:"Ciclo encerrado",detail:"O pedido ja foi concluido em campo.",action:"Encerrar o caso no atendimento e manter apenas rastreabilidade."}:n==="PRIMARIA_EM_EXECUCAO"?{tone:"info",stage:"Em execucao",detail:"O pedido ja esta com a operacao de entrega, com rota primaria ativa.",action:"Atendimento acompanha apenas excecoes; despacho ja recebeu o handoff."}:n==="SECUNDARIA_CONFIRMADA"?{tone:"warn",stage:"Aguardando inicio de rota",detail:"O pedido foi encaixado na secundaria e depende de acao do despacho para avancar.",action:"Avisar o despacho sobre rota pronta e janela do cliente."}:a==="CONFIRMADO"?{tone:"warn",stage:"Confirmado para despacho",detail:"O pedido ja saiu do atendimento, mas ainda precisa ser roteirizado.",action:"Repassar prioridade, forma de pagamento e observacoes do cadastro."}:{tone:"info",stage:"Entrou na fila",detail:"Pedido registrado e aguardando a cadencia normal do despacho.",action:"Checar o cockpit operacional para acompanhar a entrada nas filas."}}function Ye(e){const a=new Date().toISOString();return{pedidoId:e,clienteId:null,telefone:"",telefoneNormalizado:"",quantidadeGaloes:0,atendenteId:0,origemCanal:"MANUAL",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",requestKey:null,clienteCriado:!1,idempotente:!1,statusAtual:null,timeline:null,execucao:null,saldo:null,extrato:null,financeStatus:"idle",notes:["Pedido importado por consulta manual."],error:null,createdAt:a,updatedAt:a,lastSyncAt:null}}function X(e,a){return a.updatedAt.localeCompare(e.updatedAt)}function M(e){return[...new Set(e.map(a=>l(a)).filter(Boolean))]}const We=20,Ze=8;class xe extends Error{status;detail;constructor(a,n,t=null){super(a),this.name="ApiError",this.status=n,this.detail=t}}function ea(e,a){return`${String(e).replace(/\/+$/,"")}${a}`}async function y(e,a,n={}){const t=await fetch(ea(e,a),{method:n.method||"GET",headers:{...n.body?{"Content-Type":"application/json"}:{},...n.headers||{}},body:n.body?JSON.stringify(n.body):void 0}),o=await t.json().catch(()=>({}));if(!t.ok)throw new xe(o.erro||o.message||`HTTP ${t.status}`,t.status,o.detalhe||null);return o}function aa(){return{health:"unknown",painel:"unknown",eventos:"unknown",mapa:"unknown"}}async function na(e){const a=aa(),n=[],[t,o,i,r]=await Promise.allSettled([y(e,"/health"),y(e,"/api/operacao/painel"),y(e,`/api/operacao/eventos?limite=${We}`),y(e,"/api/operacao/mapa")]),b=q(t,"health",a,n),f=q(o,"painel",a,n),A=q(i,"eventos",a,n),I=q(r,"mapa",a,n);if(!b&&!f&&!A&&!I)throw new Error("Nao foi possivel carregar nenhum read model operacional.");return{health:b,painel:f,eventos:A,mapa:I,readiness:a,partialErrors:n,fetchedAt:new Date().toISOString()}}function q(e,a,n,t){return e.status==="fulfilled"?(n[a]="ok",e.value):(n[a]="error",t.push(`${a}: ${e.reason instanceof Error?e.reason.message:"falha"}`),null)}function ge(e){return e instanceof xe&&[400,404,405,501].includes(e.status)}async function ta(e,a,n){return y(e,"/api/atendimento/pedidos",{method:"POST",headers:n?{"Idempotency-Key":n}:void 0,body:a})}async function oa(e,a){return y(e,`/api/pedidos/${a}/timeline`)}async function ia(e,a){return y(e,`/api/pedidos/${a}/execucao`)}async function ra(e,a){return y(e,`/api/financeiro/clientes/${a}/saldo`)}async function sa(e,a,n=Ze){return y(e,`/api/financeiro/clientes/${a}/extrato?limit=${n}`)}function C(e){return e instanceof Error?e.message:"Falha ao sincronizar a operacao."}function la(e){const{root:a,router:n,store:t,persistApiBase:o,persistAutoRefresh:i,persistAtendimentoState:r}=e,b=()=>{const u=t.getState().atendimento;r({draft:u.draft,lookupPhone:u.lookupPhone,lookupPedidoId:u.lookupPedidoId,sessionCases:u.sessionCases,activeCaseId:u.activeCaseId})},f=(u,g=!0)=>{t.updateAtendimento(u,{notify:g}),b()},A=async()=>{if(t.getState().sync.status!=="loading"){t.startSync();try{const u=await na(t.getState().connection.apiBase);t.finishSync(u)}catch(u){t.failSync(C(u))}}},I=async u=>{if(!Number.isInteger(u)||u<=0)return;let g=t.getState().atendimento.sessionCases.find(c=>c.pedidoId===u);if(g)f(c=>({...c,activeCaseId:u,lastError:null}));else{const c=Ye(u);g=c,f(p=>({...p,sessionCases:V(p.sessionCases,c),activeCaseId:u,lastError:null}))}f(c=>({...c,syncingCaseId:u}));try{const c=t.getState().atendimento.sessionCases.find(w=>w.pedidoId===u);if(!c)throw new Error(`Pedido #${u} nao encontrado na sessao de atendimento.`);const[p,m,h,x]=await Promise.allSettled([oa(t.getState().connection.apiBase,u),ia(t.getState().connection.apiBase,u),c.clienteId?ra(t.getState().connection.apiBase,c.clienteId):Promise.resolve(null),c.clienteId?sa(t.getState().connection.apiBase,c.clienteId):Promise.resolve(null)]),D=[];let S=c.financeStatus,_=null;const G=me(c,{timeline:p.status==="fulfilled"?p.value:c.timeline,execucao:m.status==="fulfilled"?m.value:c.execucao});p.status==="rejected"&&D.push(C(p.reason)),m.status==="rejected"&&D.push(C(m.reason)),h.status==="fulfilled"&&h.value?S="ok":h.status==="rejected"&&(ge(h.reason)?(S="unavailable",D.push("Saldo/extrato nao estao disponiveis nesta base da app.")):(S="error",_=C(h.reason))),x.status==="fulfilled"&&x.value?S="ok":x.status==="rejected"&&(ge(x.reason)?S="unavailable":S!=="unavailable"&&(S="error",_=C(x.reason)));const we=me(G,{saldo:h.status==="fulfilled"?h.value:G.saldo,extrato:x.status==="fulfilled"?x.value:G.extrato,financeStatus:S,error:_,notes:D});f(w=>({...w,syncingCaseId:null,lastSuccess:`Pedido #${u} sincronizado com timeline e execucao.`,sessionCases:V(w.sessionCases,we)}))}catch(c){f(p=>({...p,syncingCaseId:null,lastError:C(c)}))}},N=async u=>{const g=pe(new FormData(u));f(p=>({...p,draft:g,lastError:null,lastSuccess:null}));let c;try{c=ne(g)}catch(p){f(m=>({...m,lastError:C(p)}));return}f(p=>({...p,submitting:!0}));try{const p=await ta(t.getState().connection.apiBase,c.payload,c.idempotencyKey),m=Ve(g,p);f(h=>({...h,submitting:!1,draft:Je(h.draft),activeCaseId:p.pedidoId,lastSuccess:`Pedido #${p.pedidoId} criado para cliente ${p.clienteId}.`,sessionCases:V(h.sessionCases,m)})),await I(p.pedidoId),await A()}catch(p){f(m=>({...m,submitting:!1,lastError:C(p)}))}},H=async u=>{const g=new FormData(u),c=String(g.get("lookupPhone")||"").trim(),p=Number(String(g.get("lookupPedidoId")||"").trim());if(f(h=>({...h,lookupPhone:c,lookupPedidoId:Number.isInteger(p)&&p>0?String(p):"",lastError:null})),p>0){await I(p);return}const m=Se(t.getState().atendimento,c);if(m.length===0){f(h=>({...h,lastError:"Nenhum atendimento da sessao corresponde a esse telefone."}));return}f(h=>({...h,activeCaseId:m[0].pedidoId}))},E=async()=>{await A(),t.getState().activeModule==="atendimento"&&t.getState().atendimento.activeCaseId&&await I(t.getState().atendimento.activeCaseId)},L=u=>{const c=(u.target instanceof HTMLElement?u.target:null)?.closest("[data-action]");if(!c||!a.contains(c))return;const p=c.dataset.action;if(p==="save-api-base"){t.commitConnection(),o(t.getState().connection.apiBase),E();return}if(p==="refresh-snapshot"){E();return}if(p==="navigate"){const m=c.dataset.moduleId;m&&(n.navigate(m),t.setActiveModule(m),E());return}if(p==="focus-atendimento-case"){const m=Number(c.dataset.pedidoId);if(!Number.isInteger(m)||m<=0)return;f(h=>({...h,activeCaseId:m,lastError:null}));return}if(p==="refresh-atendimento-case"){const m=Number(c.dataset.pedidoId);if(!Number.isInteger(m)||m<=0)return;I(m)}},O=u=>{const g=u.target;if(!(g instanceof HTMLInputElement||g instanceof HTMLSelectElement||g instanceof HTMLTextAreaElement)||!a.contains(g))return;if(g instanceof HTMLInputElement&&g.id==="api-base-input"){t.setConnectionDraft(g.value);return}if(g instanceof HTMLInputElement&&g.id==="auto-refresh-input"){const m=!!g.checked;t.setAutoRefresh(m),i(m);return}const c=g.closest("form"),p=u.type==="change";if(c?.id==="atendimento-form"){const m=pe(new FormData(c));f(h=>({...h,draft:m}),p);return}if(c?.id==="atendimento-lookup-form"){const m=new FormData(c);f(h=>({...h,lookupPhone:String(m.get("lookupPhone")||"").trim(),lookupPedidoId:String(m.get("lookupPedidoId")||"").trim()}),p)}},de=u=>{const g=u.target;if(!(!(g instanceof HTMLFormElement)||!a.contains(g))){if(g.id==="atendimento-form"){u.preventDefault(),N(g);return}g.id==="atendimento-lookup-form"&&(u.preventDefault(),H(g))}};return{bind(){a.addEventListener("click",L),a.addEventListener("input",O),a.addEventListener("change",O),a.addEventListener("submit",de)},refreshSnapshot:E,dispose(){a.removeEventListener("click",L),a.removeEventListener("input",O),a.removeEventListener("change",O),a.removeEventListener("submit",de),n.dispose()}}}const te=[{id:"cockpit",hash:"#/cockpit",label:"Cockpit",description:"Visao consolidada da operacao, dos alertas e dos read models.",status:"active"},{id:"atendimento",hash:"#/atendimento",label:"Atendimento",description:"Busca de cliente na sessao, criacao segura de pedido e handoff conectado ao despacho.",status:"active"},{id:"despacho",hash:"#/despacho",label:"Despacho",description:"Cockpit principal para fila operacional, camadas de frota, risco e acoes de saida.",status:"planned"},{id:"entregador",hash:"#/entregador",label:"Entregador",description:"Base reservada para roteiro, progresso de rota, deep link e eventos terminais.",status:"planned"}];function B(e){const a=te.find(n=>n.id===e);if(!a)throw new Error(`Modulo desconhecido: ${e}`);return a}function fe(e){return B(e).hash}function da(e){return te.some(a=>a.id===e)}function Q(e){const a=String(e||"").replace(/^#\/?/,"").trim().toLowerCase();return da(a)?a:"cockpit"}function ca(e){const a=e.targetWindow??window;let n=null;const t=()=>{n!==null&&(a.clearInterval(n),n=null)},o=()=>{t(),n=a.setInterval(e.onTick,e.intervalMs)};return{sync(i){if(!i){t();return}o()},stop(){t()}}}function ua(e,a=window){let n=Q(a.location.hash);const t=()=>{const o=Q(a.location.hash);o!==n&&(n=o,e(o))};return{getCurrentModule(){return n},navigate(o){n=o;const i=fe(o);a.location.hash!==i&&(a.location.hash=i)},start(){a.addEventListener("hashchange",t);const o=fe(n);a.location.hash!==o&&(a.location.hash=o)},dispose(){a.removeEventListener("hashchange",t)}}}function pa(e){let a=e;const n=new Set,t=()=>{n.forEach(i=>i(a))},o=(i,r={})=>{a=i(a),r.notify!==!1&&t()};return{getState(){return a},subscribe(i){return n.add(i),()=>{n.delete(i)}},setConnectionDraft(i){o(r=>({...r,connection:{...r.connection,apiBaseDraft:i}}),{notify:!1})},commitConnection(){o(i=>{const r=i.connection.apiBaseDraft.trim()||i.connection.apiBase;return{...i,connection:{...i.connection,apiBase:r,apiBaseDraft:r}}})},setAutoRefresh(i){o(r=>({...r,connection:{...r.connection,autoRefresh:i}}))},setActiveModule(i){o(r=>({...r,activeModule:i}))},updateAtendimento(i,r){o(b=>({...b,atendimento:i(b.atendimento)}),r)},setEntregadorId(i){o(r=>({...r,entregador:{...r.entregador,entregadorId:i,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}}))},startSync(){o(i=>({...i,sync:{...i.sync,status:"loading",lastError:null}}))},finishSync(i){o(r=>({...r,snapshot:i,sync:{status:"ready",lastError:null}}))},failSync(i){o(r=>({...r,sync:{status:"error",lastError:i}}))},startDespachoRouteStart(){o(i=>({...i,despacho:{...i.despacho,routeStart:{status:"loading",lastError:null},lastRouteStart:null}}))},finishDespachoRouteStart(i){o(r=>({...r,despacho:{routeStart:{status:"ready",lastError:null},lastRouteStart:{tone:i.idempotente?"warn":"ok",title:i.idempotente?"Acao reconhecida como idempotente":"Rota pronta iniciada",detail:`R${i.rotaId} disparada para o pedido ${i.pedidoId} e entrega ${i.entregaId}.`,payload:i}}}))},failDespachoRouteStart(i){o(r=>({...r,despacho:{routeStart:{status:"error",lastError:i},lastRouteStart:{tone:"danger",title:"Falha ao iniciar rota pronta",detail:i,payload:null}}}))},startEntregadorSync(){o(i=>({...i,entregador:{...i.entregador,sync:{status:"loading",lastError:null}}}))},finishEntregadorSync(i,r){o(b=>({...b,entregador:{...b.entregador,roteiro:i,fetchedAt:r,sync:{status:"ready",lastError:null}}}))},failEntregadorSync(i){o(r=>({...r,entregador:{...r.entregador,sync:{status:"error",lastError:i}}}))},startEntregadorAction(){o(i=>({...i,entregador:{...i.entregador,action:{status:"loading",lastError:null}}}))},finishEntregadorAction(i){o(r=>({...r,entregador:{...r.entregador,action:{status:"ready",lastError:null},lastAction:i}}))},failEntregadorAction(i){o(r=>({...r,entregador:{...r.entregador,action:{status:"error",lastError:i},lastAction:{tone:"danger",title:"Acao rejeitada",detail:i,payload:null}}}))}}}function d(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function U(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function $(e,a){return`<span class="pill ${a}">${d(e)}</span>`}function J(e,a,n){return`<option value="${d(e)}" ${e===n?"selected":""}>${d(a)}</option>`}function oe(e){const a=String(e.statusAtual||"").toUpperCase();return a==="ENTREGUE"?"ok":a==="CANCELADO"?"danger":a==="CONFIRMADO"?"warn":a==="EM_ROTA"?"info":"muted"}function ma(e){const a=e.snapshot?.painel;return`
    <div class="atendimento-pulse-grid">
      ${[{label:"Fila nova",value:String(a?.filas.pendentesElegiveis.length??"-"),copy:"Pedidos que ainda vao entrar em triagem."},{label:"Rotas prontas",value:String(a?.rotas.planejadas.length??"-"),copy:"Demandas ja comprometidas aguardando acao do despacho."},{label:"Casos na sessao",value:String(e.atendimento.sessionCases.length),copy:"Atendimentos recentes preservados localmente para handoff."},{label:"Ultima leitura",value:e.snapshot?.fetchedAt?U(e.snapshot.fetchedAt):"-",copy:"Base operacional usada como pano de fundo do atendimento."}].map(t=>`
            <article class="pulse-card atendimento-pulse-card">
              <p class="label">${d(t.label)}</p>
              <strong>${d(t.value)}</strong>
              <p>${d(t.copy)}</p>
            </article>
          `).join("")}
    </div>
  `}function ga(e){const a=Ee(e.atendimento.draft,e.atendimento.sessionCases),n=Ge(e.atendimento.draft),t=[];return a.length>0&&t.push(`
      <div class="notice notice-danger">
        <strong>Bloqueios antes de enviar:</strong> ${d(a.join(" | "))}
      </div>
    `),n.length>0&&t.push(`
      <div class="notice notice-warn">
        <strong>Pontos de atencao:</strong> ${d(n.join(" | "))}
      </div>
    `),e.atendimento.lastError&&t.push(`
      <div class="notice notice-danger">
        <strong>Falha no atendimento:</strong> ${d(e.atendimento.lastError)}
      </div>
    `),e.atendimento.lastSuccess&&t.push(`
      <div class="notice notice-ok">
        <strong>Atendimento registrado:</strong> ${d(e.atendimento.lastSuccess)}
      </div>
    `),e.snapshot?.partialErrors&&e.snapshot.partialErrors.length>0&&t.push(`
      <div class="notice notice-warn">
        <strong>Leitura operacional parcial:</strong> ${d(e.snapshot.partialErrors.join(" | "))}
      </div>
    `),t.join("")}function fa(e){const a=e.atendimento.draft,n=Ee(a,e.atendimento.sessionCases),t=a.janelaTipo==="HARD",o=a.origemCanal==="MANUAL",i=a.origemCanal==="WHATSAPP"||a.origemCanal==="BINA_FIXO"||a.origemCanal==="TELEFONIA_FIXO";return`
    <section class="panel atendimento-form-panel">
      <div class="panel-header">
        <div>
          <h2>Novo atendimento</h2>
          <p class="section-copy">Entrada guiada para pedido telefonico com regras explicitas e rastro de idempotencia.</p>
        </div>
        ${$(e.atendimento.submitting?"enviando":"pronto para envio",e.atendimento.submitting?"warn":"ok")}
      </div>
      <form id="atendimento-form" class="atendimento-form">
        <div class="form-grid form-grid-primary">
          <label class="field">
            <span>Telefone</span>
            <input name="telefone" type="tel" value="${d(a.telefone)}" placeholder="(38) 99876-1234" required />
          </label>
          <label class="field">
            <span>Quantidade de galoes</span>
            <input name="quantidadeGaloes" type="number" min="1" step="1" value="${d(a.quantidadeGaloes)}" required />
          </label>
          <label class="field">
            <span>Atendente ID</span>
            <input name="atendenteId" type="number" min="1" step="1" value="${d(a.atendenteId)}" required />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Metodo de pagamento</span>
            <select name="metodoPagamento">
              ${$e.map(r=>J(r,r.replace("_"," "),a.metodoPagamento)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Janela</span>
            <select name="janelaTipo">
              ${Ae.map(r=>J(r,r,a.janelaTipo)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Origem do canal</span>
            <select name="origemCanal">
              ${be.map(r=>J(r,r.replace("_"," "),a.origemCanal)).join("")}
            </select>
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${t?"":"field-disabled"}">
            <span>Janela inicio</span>
            <input name="janelaInicio" type="time" value="${d(a.janelaInicio)}" ${t?"":"disabled"} />
          </label>
          <label class="field ${t?"":"field-disabled"}">
            <span>Janela fim</span>
            <input name="janelaFim" type="time" value="${d(a.janelaFim)}" ${t?"":"disabled"} />
          </label>
          <label class="field">
            <span>Nome do cliente</span>
            <input name="nomeCliente" type="text" value="${d(a.nomeCliente)}" placeholder="Condominio Horizonte" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Endereco</span>
            <input name="endereco" type="text" value="${d(a.endereco)}" placeholder="Rua da Operacao, 99" />
          </label>
          <label class="field">
            <span>Latitude</span>
            <input name="latitude" type="text" value="${d(a.latitude)}" placeholder="-16.7200" />
          </label>
          <label class="field">
            <span>Longitude</span>
            <input name="longitude" type="text" value="${d(a.longitude)}" placeholder="-43.8600" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${i?"":"field-disabled"}">
            <span>sourceEventId</span>
            <input name="sourceEventId" type="text" value="${d(a.sourceEventId)}" placeholder="evt-whatsapp-001" ${i?"":"disabled"} />
          </label>
          <label class="field ${o?"":"field-disabled"}">
            <span>manualRequestId</span>
            <input name="manualRequestId" type="text" value="${d(a.manualRequestId)}" placeholder="manual-ui-..." ${o?"":"disabled"} />
          </label>
          <label class="field">
            <span>externalCallId</span>
            <input name="externalCallId" type="text" value="${d(a.externalCallId)}" placeholder="call-center-001" />
          </label>
        </div>

        <div class="form-actions">
          <button class="button primary" type="submit" ${e.atendimento.submitting||n.length>0?"disabled":""}>
            ${e.atendimento.submitting?"Registrando...":"Registrar pedido"}
          </button>
          <p class="form-footnote">O envio usa a API real e preserva uma chave de idempotencia para retries seguros.</p>
        </div>
      </form>
    </section>
  `}function ha(e){return e.financeStatus==="unavailable"?'<div class="notice notice-warn"><strong>Financeiro nao acoplado:</strong> a base atual nao expoe saldo/extrato do cliente nesta app.</div>':e.financeStatus==="error"?'<div class="notice notice-danger"><strong>Financeiro com falha:</strong> nao foi possivel sincronizar saldo/extrato agora.</div>':!e.saldo&&!e.extrato?'<p class="empty-copy">Saldo e extrato ainda nao sincronizados para este cliente.</p>':`
    <div class="finance-grid">
      <article class="metric-card finance-card">
        <p class="metric-label">Saldo de vales</p>
        <p class="metric-value">${d(e.saldo?.quantidade??"-")}</p>
      </article>
      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Extrato recente</h3>
          ${$(e.extrato?String(e.extrato.itens.length):"0","info")}
        </div>
        <div class="timeline-list">
          ${e.extrato&&e.extrato.itens.length>0?e.extrato.itens.slice(0,5).map(va).join(""):'<p class="empty-copy">Nenhum movimento recente retornado.</p>'}
        </div>
      </section>
    </div>
  `}function va(e){return`
    <article class="timeline-item compact">
      <div class="timeline-topline">
        <strong>${d(e.tipo)}</strong>
        ${$(`saldo ${e.saldoApos}`,e.tipo==="CREDITO"?"ok":"warn")}
      </div>
      <p class="mono">${d(e.quantidade)} galao(oes) · ${d(U(e.data))}</p>
      <p class="empty-copy">${d(e.observacao||e.registradoPor)}</p>
    </article>
  `}function ba(e){return`
    <section class="timeline-shell">
      <div class="context-block-header">
        <h3>Timeline do pedido</h3>
        ${$(e.statusAtual||"sem status",oe(e))}
      </div>
      <div class="timeline-list">
        ${e.timeline&&e.timeline.eventos.length>0?e.timeline.eventos.map(a=>`
                    <article class="timeline-item">
                      <div class="timeline-topline">
                        <strong>${d(a.deStatus)} → ${d(a.paraStatus)}</strong>
                        ${$(a.origem,"muted")}
                      </div>
                      <p class="mono">${d(U(a.timestamp))}</p>
                      ${a.observacao?`<p>${d(a.observacao)}</p>`:""}
                    </article>
                  `).join(""):'<p class="empty-copy">Timeline ainda nao carregada para este pedido.</p>'}
      </div>
    </section>
  `}function $a(e){const a=Ke(e.atendimento),n=Se(e.atendimento,e.atendimento.lookupPhone),t=Qe(a),o=a?ha(a):'<p class="empty-copy">Selecione um atendimento para ver contexto financeiro e historico.</p>',i=a?ba(a):'<p class="empty-copy">Nenhum atendimento em foco.</p>',r=a&&e.atendimento.syncingCaseId===a.pedidoId;return`
    <section class="panel atendimento-context-panel">
      <div class="panel-header">
        <div>
          <h2>Contexto e handoff</h2>
          <p class="section-copy">Consulta rapida por pedido, ressincronizacao com a API e passagem clara para o despacho.</p>
        </div>
        ${$(a?`pedido #${a.pedidoId}`:"sem foco",a?oe(a):"muted")}
      </div>

      <form id="atendimento-lookup-form" class="lookup-form">
        <label class="field">
          <span>Buscar por telefone da sessao</span>
          <input name="lookupPhone" type="text" value="${d(e.atendimento.lookupPhone)}" placeholder="38998761234" />
        </label>
        <label class="field">
          <span>Consultar pedidoId na API</span>
          <input name="lookupPedidoId" type="number" min="1" value="${d(e.atendimento.lookupPedidoId)}" placeholder="8421" />
        </label>
        <div class="toolbar-actions lookup-actions">
          <button class="button secondary" type="submit">Buscar contexto</button>
          ${a?`<button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${a.pedidoId}" type="button" ${r?"disabled":""}>${r?"Atualizando...":"Atualizar pedido"}</button>`:""}
        </div>
      </form>

      ${a?`
            <article class="handoff-card tone-${t.tone}">
              <div class="priority-topline">
                ${$(t.stage,t.tone)}
                <strong>${d(a.nomeCliente||ye(a.telefone)||`Pedido #${a.pedidoId}`)}</strong>
              </div>
              <p>${d(t.detail)}</p>
              <p class="priority-action">${d(t.action)}</p>
              <div class="context-metrics">
                <article class="metric-card compact">
                  <p class="metric-label">Pedido</p>
                  <p class="metric-value small">#${d(a.pedidoId)}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Cliente</p>
                  <p class="metric-value small">${d(a.clienteId??"-")}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Janela</p>
                  <p class="metric-value small">${d(Ce(a))}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Execucao</p>
                  <p class="metric-value small">${d(a.execucao?.camada||a.statusAtual||"-")}</p>
                </article>
              </div>
              <div class="tag-row">
                ${a.requestKey?$(`key ${a.requestKey}`,"muted"):""}
                ${a.execucao?.rotaId?$(`rota ${a.execucao.rotaId}`,"info"):""}
                ${a.execucao?.entregaId?$(`entrega ${a.execucao.entregaId}`,"info"):""}
                ${$(a.metodoPagamento||"NAO_INFORMADO",a.metodoPagamento==="VALE"?"warn":"muted")}
                ${a.clienteCriado?$("cliente criado agora","ok"):""}
                ${a.idempotente?$("retry idempotente","info"):""}
              </div>
              ${a.error?`<div class="notice notice-danger"><strong>Ultimo erro:</strong> ${d(a.error)}</div>`:""}
              ${a.notes.length>0?`<div class="notice notice-warn"><strong>Notas de contexto:</strong> ${d(a.notes.join(" | "))}</div>`:""}
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
          ${$(String(n.length),"muted")}
        </div>
        <div class="session-list">
          ${n.length>0?n.map(b=>Aa(b,e.atendimento.activeCaseId,e.atendimento.syncingCaseId)).join(""):'<p class="empty-copy">Nenhum atendimento da sessao corresponde a essa busca.</p>'}
        </div>
      </section>
    </section>
  `}function Aa(e,a,n){const t=oe(e),o=a===e.pedidoId,i=n===e.pedidoId;return`
    <article class="session-card ${o?"is-active":""}">
      <div class="queue-card-header">
        <strong>${d(e.nomeCliente||ye(e.telefone)||`Pedido #${e.pedidoId}`)}</strong>
        ${$(e.statusAtual||"sem status",t)}
      </div>
      <p class="mono">Pedido #${d(e.pedidoId)} · cliente ${d(e.clienteId??"-")}</p>
      <p class="empty-copy">${d(Ce(e))} · ${d(e.metodoPagamento||"NAO_INFORMADO")}</p>
      <p class="empty-copy">Atualizado ${d(U(e.lastSyncAt||e.updatedAt))}</p>
      <div class="toolbar-actions inline-actions">
        <button class="button secondary" data-action="focus-atendimento-case" data-pedido-id="${e.pedidoId}" type="button">
          ${o?"Em foco":"Trazer para foco"}
        </button>
        <button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${e.pedidoId}" type="button" ${i?"disabled":""}>
          ${i?"Atualizando...":"Atualizar"}
        </button>
      </div>
    </article>
  `}function Ia(e){return`
    <section class="atendimento-workspace">
      ${ma(e)}
      ${ga(e)}
      <div class="atendimento-grid">
        ${fa(e)}
        ${$a(e)}
      </div>
    </section>
  `}function s(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function v(e,a){return`<span class="pill ${a}">${s(e)}</span>`}function Ea(e){return`
    <article class="signal-card tone-${e.tone}">
      <p class="panel-kicker">${s(e.label)}</p>
      <strong>${s(e.value)}</strong>
      <p>${s(e.detail)}</p>
    </article>
  `}function ya(e){return`
    <article class="metric-card tone-${e.tone}">
      <p class="metric-label">${s(e.label)}</p>
      <p class="metric-value">${s(e.value)}</p>
      <p class="metric-detail">${s(e.detail)}</p>
    </article>
  `}function Sa(e){return`
    <article class="priority-card tone-${e.tone}">
      <div class="priority-topline">
        ${v(e.badge,e.tone)}
        <strong>${s(e.title)}</strong>
      </div>
      <p>${s(e.detail)}</p>
      <p class="priority-action">${s(e.action)}</p>
    </article>
  `}function he(e){return`
    <article class="route-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.detail)}</p>
        </div>
        ${v(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="route-stat-row mono">
        ${e.meta.map(a=>`<span>${s(a)}</span>`).join("")}
      </div>
    </article>
  `}function Ca(e){return`
    <article class="queue-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.summary)}</p>
        </div>
        ${v(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="queue-card-stats mono">
        ${e.lines.map(a=>`<span>${s(a)}</span>`).join("")}
      </div>
    </article>
  `}function xa(e){return`
    <section class="queue-lane tone-${e.tone}">
      <div class="queue-lane-header">
        <div>
          <p class="panel-kicker">Etapa ${s(e.step)}</p>
          <h3>${s(e.title)}</h3>
          <p class="queue-lane-copy">${s(e.summary)}</p>
        </div>
        ${v(`${e.count} item(ns)`,e.tone)}
      </div>
      <div class="queue-list">
        ${e.cards.length?e.cards.map(Ca).join(""):`<p class="empty-copy">${s(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function Ra(e){return`
    <article class="readiness-item tone-${e.tone}">
      <p class="panel-kicker">${s(e.label)}</p>
      <strong>${s(e.title)}</strong>
      <p>${s(e.detail)}</p>
    </article>
  `}function Pa(e){return`
    <article class="event-item tone-${e.tone}">
      <div class="event-topline">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.subject)}</p>
        </div>
        ${v(e.badgeLabel,e.badgeTone)}
      </div>
      <p>${s(e.detail)}</p>
      <p class="event-meta mono">${s(e.meta)}</p>
    </article>
  `}function Ta(e){return`
    <article class="micro-card tone-${e.tone}">
      <p class="panel-kicker">${s(e.label)}</p>
      <strong>${s(e.value)}</strong>
      <p>${s(e.detail)}</p>
    </article>
  `}function ja(e){return`
    <article class="route-card large tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${s(e.title)}</p>
          <p class="card-copy">${s(e.summary)}</p>
        </div>
        ${v(e.badgeLabel,e.badgeTone)}
      </div>
      <p class="mono">${s(e.detail)}</p>
      <div class="tag-row">
        ${e.tags.map(a=>v(a,"muted")).join("")}
      </div>
    </article>
  `}function ka(e){return`
    <section class="panel cockpit-overview">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Cockpit operacional</p>
          <h2>${s(e.headline)}</h2>
          <p class="section-copy">${s(e.executiveSummary)}</p>
        </div>
        ${v(e.modeLabel,e.modeTone)}
      </div>
      <div class="hero-signals">
        ${e.signals.map(Ea).join("")}
      </div>
    </section>

    <section class="metrics-grid">
      ${e.metrics.map(ya).join("")}
    </section>

    <section class="panel priority-panel">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Direcionamento imediato</p>
          <h2>O que merece atencao primeiro</h2>
          <p class="section-copy">${s(e.nextActionDetail)}</p>
        </div>
        ${v(e.nextAction,e.nextActionTone)}
      </div>
      <div class="priority-layout">
        <article class="priority-lead tone-${e.leadAlert.tone}">
          <div class="priority-topline">
            ${v("foco da rodada",e.leadAlert.tone)}
            <strong>${s(e.leadAlert.title)}</strong>
          </div>
          <p>${s(e.leadAlert.detail)}</p>
          <p class="priority-action">${s(e.leadAlert.action)}</p>
        </article>
        <div class="priority-support">
          ${e.supportingAlerts.length?e.supportingAlerts.map(Sa).join(""):`
                <article class="priority-card tone-ok">
                  <div class="priority-topline">
                    ${v("estavel","ok")}
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
            ${v(e.panelUpdatedAt,"muted")}
          </div>
          <div class="routes-board">
            <article class="route-group tone-${e.activeRoutes.length>0?"info":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas em campo</p>
                  <strong class="route-group-count">${s(String(e.activeRoutes.length))}</strong>
                </div>
                ${v(e.activeRoutes.length>0?`${e.activeRoutes.length} rota(s)`:"sem rota",e.activeRoutes.length>0?"info":"ok")}
              </div>
              <p class="section-copy">${s(e.activeRoutes.length>0?"Entregas rodando e pedindo acompanhamento de execucao.":"Nenhuma rota ativa na leitura atual.")}</p>
              <div class="route-list">
                ${e.activeRoutes.length?e.activeRoutes.map(he).join(""):'<p class="empty-copy">Sem rota ativa.</p>'}
              </div>
            </article>
            <article class="route-group tone-${e.plannedRoutes.length>0?"warn":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas prontas</p>
                  <strong class="route-group-count">${s(String(e.plannedRoutes.length))}</strong>
                </div>
                ${v(e.plannedRoutes.length>0?`${e.plannedRoutes.length} rota(s)`:"sem rota",e.plannedRoutes.length>0?"warn":"ok")}
              </div>
              <p class="section-copy">${s(e.plannedRoutes.length>0?"Carga ja comprometida aguardando decisao para ganhar rua.":"Nenhuma rota pronta aguardando liberacao.")}</p>
              <div class="route-list">
                ${e.plannedRoutes.length?e.plannedRoutes.map(he).join(""):'<p class="empty-copy">Sem rota planejada.</p>'}
              </div>
            </article>
          </div>
          <div class="queue-grid">
            ${e.queueLanes.map(xa).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Mapa operacional</p>
              <h2>Camadas e rotas</h2>
              <p class="section-copy">${s(e.pulses.find(a=>a.label==="Rotas")?.value||"Sem resumo de rotas.")}</p>
            </div>
            ${e.mapDeposit?v(e.mapDeposit,"muted"):""}
          </div>
          <div class="micro-grid">
            ${e.mapSummaryCards.map(Ta).join("")}
          </div>
          <div class="route-list">
            ${e.mapRoutes.length?e.mapRoutes.map(ja).join(""):'<p class="empty-copy">Sem rotas mapeadas.</p>'}
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
              ${v(e.readinessStatus.title,e.readinessStatus.tone)}
            </div>
          </div>
          <div class="readiness-grid">
            ${e.readinessItems.map(Ra).join("")}
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
            ${v(e.eventBadgeLabel,e.eventBadgeTone)}
          </div>
          <div class="event-list">
            ${e.events.length?e.events.map(Pa).join(""):'<p class="empty-copy">Nenhum evento retornado.</p>'}
          </div>
        </section>
      </aside>
    </main>
  `}function Na(e){if(!e||!e.painel)return{alerts:[{tone:"warn",title:"Sem leitura operacional suficiente",detail:"A interface ainda nao conseguiu carregar o painel principal.",action:"Validar API base e atualizar a sincronizacao."}],headline:"Aguardando dados operacionais",queueSummary:"Fila indisponivel",routeSummary:"Rotas indisponiveis",eventSummary:"Eventos indisponiveis",executiveSummary:"A operacao ainda nao entregou leitura suficiente para orientar uma decisao com seguranca.",modeLabel:"Aguardando leitura",modeTone:"warn",nextAction:"Validar conexao",nextActionDetail:"Conferir API base e executar nova sincronizacao antes de operar.",nextActionTone:"warn",confidenceLabel:"Baixa confianca",confidenceDetail:"Sem painel principal, qualquer decisao pode estar desatualizada.",confidenceTone:"danger"};const a=e.painel,n=e.mapa,t=e.eventos?.eventos||[],o=[];(e.health?.status!=="ok"||e.health?.database!=="ok")&&o.push({tone:"danger",title:"Infra com degradacao",detail:"API ou banco nao responderam como esperado nesta leitura.",action:"Conferir health e estabilizar a base antes de operar manualmente."}),e.partialErrors.length>0&&o.push({tone:"warn",title:"Visao parcial da operacao",detail:e.partialErrors.join(" | "),action:"Atualizar novamente antes de tomar decisao de despacho."});const i=a.filas.pendentesElegiveis.filter(L=>L.janelaTipo==="HARD").length;i>0&&o.push({tone:"danger",title:`${i} pedido(s) HARD aguardando despacho`,detail:"Esses pedidos merecem prioridade maxima porque a janela e mais restrita.",action:"Separar esses pedidos primeiro na avaliacao de fila."});const r=a.rotas.planejadas.length;r>0&&o.push({tone:"warn",title:`${r} rota(s) pronta(s) para iniciar`,detail:"Existe carga ja comprometida aguardando acao operacional.",action:"Confirmar se a capacidade da frota permite iniciar a proxima rota."});const b=Re(t);b>0&&o.push({tone:"info",title:`${b} ocorrencia(s) recente(s) de falha/cancelamento`,detail:"O feed operacional registrou eventos de excecao nas ultimas leituras.",action:"Olhar o bloco de ocorrencias para entender o contexto antes da proxima acao."});const f=qa(n);f.consistent||o.push({tone:"warn",title:"Camadas da frota fora do esperado",detail:f.message,action:"Revisar leitura de rotas antes de assumir que existe uma unica primaria e uma unica secundaria."}),o.length===0&&o.push({tone:"ok",title:"Operacao em regime estavel",detail:"Nao surgiram sinais imediatos de excecao na leitura atual.",action:"Seguir monitorando fila, eventos e rotas por cadencia normal."});const A=La(a),I=Oa(a),N=Da(a,n,f.message),H=wa(t),E=o[0];return{alerts:o.slice(0,4),headline:A,queueSummary:I,routeSummary:N,eventSummary:H,executiveSummary:`${A}. ${I} ${N}`,modeLabel:E?.title||"Operacao estavel",modeTone:E?.tone||"ok",nextAction:E?.action||"Seguir monitorando",nextActionDetail:E?.detail||"Sem excecoes imediatas na leitura atual.",nextActionTone:E?.tone||"ok",confidenceLabel:e.partialErrors.length>0?"Confianca moderada":e.health?.status==="ok"?"Confianca alta":"Confianca reduzida",confidenceDetail:e.partialErrors.length>0?"Ha leituras parciais nesta sincronizacao; valide antes de decidir.":e.health?.status==="ok"?"Painel, eventos e mapa responderam como esperado.":"Infra ou banco sinalizaram degradacao.",confidenceTone:e.partialErrors.length>0?"warn":e.health?.status==="ok"?"ok":"danger"}}function Re(e){return e.filter(a=>{const n=String(a.eventType||"").toUpperCase();return n.includes("FALHOU")||n.includes("CANCELADO")}).length}function La(e){return e.filas.pendentesElegiveis.length>0?"Fila exigindo triagem ativa":e.rotas.emAndamento.length>0?"Entrega em curso com operacao ja movimentada":e.rotas.planejadas.length>0?"Operacao pronta para iniciar nova rota":"Base limpa para acompanhamento"}function Oa(e){return`${e.pedidosPorStatus.pendente} pendente(s), ${e.pedidosPorStatus.confirmado} confirmado(s), ${e.pedidosPorStatus.emRota} em rota.`}function Da(e,a,n){const t=a?.rotas.filter(i=>i.camada==="PRIMARIA").length??0,o=a?.rotas.filter(i=>i.camada==="SECUNDARIA").length??0;return`${e.rotas.emAndamento.length} em andamento, ${e.rotas.planejadas.length} planejada(s), ${t} primaria(s), ${o} secundaria(s). ${n}`}function wa(e){if(e.length===0)return"Sem ocorrencias recentes.";const a=Re(e);return a>0?`${a} excecao(oes) recente(s) em ${e.length} evento(s) lido(s).`:`${e.length} evento(s) recente(s) sem excecao imediata.`}function qa(e){if(!e||e.rotas.length===0)return{consistent:!0,message:"Sem rotas mapeadas."};const a=e.rotas.filter(o=>o.camada==="PRIMARIA").length,n=e.rotas.filter(o=>o.camada==="SECUNDARIA").length;return a<=1&&n<=1?{consistent:!0,message:"Camadas dentro da expectativa atual."}:{consistent:!1,message:`Leitura retornou ${a} primaria(s) e ${n} secundaria(s).`}}function R(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function Fa(e){return typeof e!="number"||Number.isNaN(e)?"-":`${e.toFixed(1)}%`}function Y(e){if(!e)return"-";const a=new Date(e);if(Number.isNaN(a.getTime()))return e;const n=Date.now()-a.getTime(),t=Math.round(Math.abs(n)/6e4);if(t<1)return"agora";if(t<60)return n>=0?`${t} min atras`:`em ${t} min`;const o=Math.round(t/60);if(o<24)return n>=0?`${o} h atras`:`em ${o} h`;const i=Math.round(o/24);return n>=0?`${i} dia(s) atras`:`em ${i} dia(s)`}function P(e){return e?e.toLowerCase().split(/[_-]+/).filter(Boolean).map(a=>a.charAt(0).toUpperCase()+a.slice(1)).join(" "):"-"}function za(e){return e==="health"?"Health":e==="painel"?"Painel":e==="eventos"?"Eventos":"Mapa"}function Ma(e){return e==="ok"?"ok":e==="error"?"danger":"muted"}function Ba(e){return e==="danger"?"agir agora":e==="warn"?"atencao":e==="info"?"acompanhar":"estavel"}function Pe(e){const a=String(e.eventType||"").toUpperCase(),n=String(e.status||"").toUpperCase();return a.includes("FALHOU")||a.includes("CANCELADO")||n.includes("ERRO")||n.includes("FALHA")?"danger":a.includes("ENTREGUE")?"ok":a.includes("ROTA")?"info":"warn"}function Ua(e){return e==="PRIMARIA"?"info":"warn"}function Te(e){return(e.snapshot?.eventos?.eventos||[]).filter(n=>Pe(n)==="danger").length}function Ha(e){const a=e.snapshot?.painel,n=a?.filas.pendentesElegiveis.filter(A=>A.janelaTipo==="HARD").length??0,t=a?.rotas.emAndamento.length??0,o=a?.rotas.planejadas.length??0,i=Te(e),r=a?.indicadoresEntrega.taxaSucessoPercentual,b=a?.indicadoresEntrega.totalFinalizadas,f=a?.indicadoresEntrega.entregasConcluidas;return[{label:"Fila para triar",value:a?String(a.filas.pendentesElegiveis.length):"-",detail:a?`${a.pedidosPorStatus.pendente} pedido(s) pendente(s) na visao geral`:"aguardando leitura do painel",tone:a?n>0?"danger":a.filas.pendentesElegiveis.length>0?"warn":"ok":"muted"},{label:"Pedidos HARD",value:a?String(n):"-",detail:n>0?"janela critica exigindo prioridade maxima":"sem pedido critico escondido na fila",tone:a?n>0?"danger":"ok":"muted"},{label:"Rotas prontas",value:a?String(o):"-",detail:o>0?"aguardando decisao de liberar saida":"sem rota pronta esperando liberacao",tone:a?o>0?"warn":"ok":"muted"},{label:"Rotas em campo",value:a?String(t):"-",detail:t>0?"execucao ativa pedindo acompanhamento":"sem entrega em curso nesta rodada",tone:a?t>0?"info":"ok":"muted"},{label:"Excecoes recentes",value:e.snapshot?String(i):"-",detail:i>0?"falhas ou cancelamentos visiveis no feed":"feed recente sem falha aparente",tone:e.snapshot?i>=3?"danger":i>0?"warn":"ok":"muted"},{label:"Taxa de sucesso",value:a?Fa(r):"-",detail:a&&typeof b=="number"&&typeof f=="number"?`${f} concluida(s) em ${b} finalizada(s)`:"indicador ainda indisponivel",tone:!a||typeof r!="number"?"muted":r>=95?"ok":r>=85?"warn":"danger"}]}function _a(e){const a=e.snapshot?.readiness;return["health","painel","eventos","mapa"].map(t=>{const o=a?.[t]||"unknown";return{label:za(t),title:o==="ok"?"Leitura pronta":o==="error"?"Falhou nesta rodada":"Aguardando resposta",detail:o==="ok"?"Fonte pronta para sustentar o cockpit.":o==="error"?"Precisa de nova tentativa ou validacao manual.":"Ainda sem retorno desta fonte.",tone:Ma(o)}})}function Ga(e){const a=[];return e.snapshot?.partialErrors.length&&a.push({label:"Leitura parcial",body:e.snapshot.partialErrors.join(" | "),tone:"warn"}),e.sync.lastError&&a.push({label:"Ultimo erro",body:e.sync.lastError,tone:"danger"}),a}function Va(e){return(e.snapshot?.painel?.rotas.emAndamento||[]).map(n=>({title:`R${n.rotaId}`,detail:`Entregador ${n.entregadorId}`,meta:[`${n.pendentes} pendente(s)`,`${n.emExecucao} em execucao`],badgeLabel:"em campo",badgeTone:"info",tone:"info"}))}function Ja(e){return(e.snapshot?.painel?.rotas.planejadas||[]).map(n=>({title:`R${n.rotaId}`,detail:`Entregador ${n.entregadorId}`,meta:[`${n.pendentes} parada(s) pronta(s)`],badgeLabel:"aguardando saida",badgeTone:"warn",tone:"warn"}))}function Ka(e){const a=e.janelaTipo==="HARD";return{title:`Pedido #${e.pedidoId}`,summary:a?"Janela critica para despacho.":"Janela flexivel em triagem.",badgeLabel:a?"prioridade maxima":"fila ativa",badgeTone:a?"danger":"warn",tone:a?"danger":"warn",lines:[`${e.quantidadeGaloes} galao(oes)`,`Criado ${Y(e.criadoEm)}`,R(e.criadoEm)]}}function Xa(e){return{title:`Pedido #${e.pedidoId}`,summary:"Carga confirmada na secundaria aguardando liberacao de rota.",badgeLabel:`R${e.rotaId}`,badgeTone:"info",tone:"info",lines:[`Entregador ${e.entregadorId}`,`Ordem ${e.ordemNaRota}`,`${e.quantidadeGaloes} galao(oes)`]}}function Qa(e){return{title:`Pedido #${e.pedidoId}`,summary:`Entrega em andamento na rota ${e.rotaId}.`,badgeLabel:P(e.statusEntrega),badgeTone:"ok",tone:"ok",lines:[`Entrega ${e.entregaId}`,`Entregador ${e.entregadorId}`,`${e.quantidadeGaloes} galao(oes)`]}}function Ya(e){const a=e.snapshot?.painel?.filas,n=a?.pendentesElegiveis.filter(t=>t.janelaTipo==="HARD").length??0;return[{step:"1",title:"Triar pedidos novos",summary:n>0?`${n} pedido(s) HARD precisam abrir a decisao desta fila.`:(a?.pendentesElegiveis.length??0)>0?"Fila ativa aguardando alocacao e priorizacao.":"Entrada limpa nesta rodada.",tone:n>0?"danger":(a?.pendentesElegiveis.length??0)>0?"warn":"ok",count:a?.pendentesElegiveis.length??0,cards:(a?.pendentesElegiveis||[]).map(Ka),emptyMessage:"Nenhum pedido pendente elegivel."},{step:"2",title:"Liberar carga preparada",summary:(a?.confirmadosSecundaria.length??0)>0?"Pedidos ja encaixados em rota secundaria aguardando o aval operacional.":"Sem carga pronta aguardando liberacao.",tone:(a?.confirmadosSecundaria.length??0)>0?"warn":"ok",count:a?.confirmadosSecundaria.length??0,cards:(a?.confirmadosSecundaria||[]).map(Xa),emptyMessage:"Nenhum pedido aguardando rota secundaria."},{step:"3",title:"Acompanhar entrega em curso",summary:(a?.emRotaPrimaria.length??0)>0?"Entregas em rua que pedem monitoramento continuo.":"Sem pedido em execucao agora.",tone:(a?.emRotaPrimaria.length??0)>0?"info":"ok",count:a?.emRotaPrimaria.length??0,cards:(a?.emRotaPrimaria||[]).map(Qa),emptyMessage:"Nenhum pedido em execucao agora."}]}function Wa(e){return(e.snapshot?.eventos?.eventos||[]).slice(0,8).map(n=>{const t=Pe(n);return{title:P(n.eventType),badgeLabel:P(n.status),badgeTone:t,subject:`${P(n.aggregateType)} ${n.aggregateId??"-"}`,detail:t==="danger"?"Excecao recente com potencial de alterar a prioridade operacional.":"Movimento recente da operacao registrado no feed.",meta:n.processedEm?`${Y(n.createdEm)} · criado ${R(n.createdEm)} · processado ${R(n.processedEm)}`:`${Y(n.createdEm)} · criado ${R(n.createdEm)}`,tone:t}})}function Za(e){const a=e.snapshot?.mapa?.rotas||[],n=a.filter(i=>i.camada==="PRIMARIA").length,t=a.filter(i=>i.camada==="SECUNDARIA").length,o=a.reduce((i,r)=>i+r.paradas.length,0);return[{label:"Primarias",value:String(n),detail:"Rotas que sustentam a execucao principal.",tone:"info"},{label:"Secundarias",value:String(t),detail:"Rotas de apoio, preparacao ou contingencia.",tone:"warn"},{label:"Paradas mapeadas",value:String(o),detail:"Total de entregas representadas nesta leitura.",tone:"ok"}]}function en(e){return(e.snapshot?.mapa?.rotas||[]).map(n=>{const t=n.paradas.filter(r=>String(r.statusEntrega).toUpperCase().includes("ENTREGUE")).length,o=Math.max(n.paradas.length-t,0),i=Ua(n.camada);return{title:`R${n.rotaId} · Entregador ${n.entregadorId}`,badgeLabel:n.camada==="PRIMARIA"?"camada primaria":"camada secundaria",badgeTone:i,summary:P(n.statusRota),detail:`${n.paradas.length} parada(s) · ${n.trajeto.length} ponto(s) no trajeto · ${t} concluida(s) · ${o} aberta(s)`,tags:n.paradas.slice(0,4).map(r=>`P${r.ordemNaRota} pedido ${r.pedidoId}`),tone:i}})}function an(e){const a=Na(e.snapshot),n=a.alerts.map(o=>({badge:Ba(o.tone),tone:o.tone,title:o.title,detail:o.detail,action:o.action})),t=Te(e);return{headline:a.headline,executiveSummary:a.executiveSummary,modeLabel:a.modeLabel,modeTone:a.modeTone,nextAction:a.nextAction,nextActionDetail:a.nextActionDetail,nextActionTone:a.nextActionTone,confidenceLabel:a.confidenceLabel,confidenceDetail:a.confidenceDetail,confidenceTone:a.confidenceTone,signals:[{label:"Modo atual",value:a.modeLabel,detail:a.executiveSummary,tone:a.modeTone},{label:"Proxima decisao",value:a.nextAction,detail:a.nextActionDetail,tone:a.nextActionTone},{label:"Confianca",value:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone}],metrics:Ha(e),leadAlert:n[0],supportingAlerts:n.slice(1),pulses:[{label:"Fila",value:a.queueSummary},{label:"Rotas",value:a.routeSummary},{label:"Ocorrencias",value:a.eventSummary}],readinessStatus:{label:a.confidenceLabel,title:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone},readinessItems:_a(e),notices:Ga(e),panelUpdatedAt:R(e.snapshot?.painel?.atualizadoEm),activeRoutes:Va(e),plannedRoutes:Ja(e),queueLanes:Ya(e),eventBadgeLabel:t>0?`${t} excecao(oes)`:"sem excecao",eventBadgeTone:t>=3?"danger":t>0?"warn":"ok",events:Wa(e),mapDeposit:e.snapshot?.mapa?`Deposito ${e.snapshot.mapa.deposito.lat.toFixed(4)}, ${e.snapshot.mapa.deposito.lon.toFixed(4)}`:null,mapSummaryCards:Za(e),mapRoutes:en(e)}}function nn(e){return`
    <section class="panel module-nav">
      <div class="panel-header">
        <div>
          <h2>Fluxos da operacao</h2>
          <p class="section-copy">Cada modulo organiza a mesma operacao sob a otica do papel que esta trabalhando agora.</p>
        </div>
        ${v(B(e.activeModule).label,"info")}
      </div>
      <div class="module-tab-list">
        ${te.map(a=>`
              <button
                class="module-tab ${a.id===e.activeModule?"is-active":""}"
                type="button"
                data-action="navigate"
                data-module-id="${s(a.id)}"
              >
                <div class="module-tab-topline">
                  <strong>${s(a.label)}</strong>
                  ${v(a.status==="active"?"ativo":"planejado",a.status==="active"?"ok":"muted")}
                </div>
                <p>${s(a.description)}</p>
              </button>
            `).join("")}
      </div>
    </section>
  `}function tn(e){const a=B(e.activeModule);return`
    <section class="panel">
      <div class="panel-header">
        <div>
          <h2>${s(a.label)}</h2>
          <p class="section-copy">${s(a.description)}</p>
        </div>
        ${v("em preparacao","warn")}
      </div>
      <div class="notice notice-warn">
        <strong>Proxima etapa:</strong> este fluxo ja esta previsto na shell modular e entra nos PRs seguintes.
      </div>
    </section>
  `}function on(e){return e.activeModule==="atendimento"?Ia(e):e.activeModule!=="cockpit"?tn(e):ka(an(e))}function je(e,a){const n=B(a.activeModule),t=a.snapshot?.partialErrors.length??0;e.innerHTML=`
    <div class="app-shell">
      <header class="hero">
        <div class="hero-copy-block">
          <p class="eyebrow">Agua Viva</p>
          <h1>Operacao web por papel</h1>
          <p class="hero-copy">${s(n.description)}</p>
          <div class="hero-meta">
            ${v("dados reais","info")}
            ${v(a.connection.autoRefresh?"auto-refresh ligado":"auto-refresh desligado",a.connection.autoRefresh?"ok":"muted")}
            ${v(a.snapshot?.fetchedAt?R(a.snapshot.fetchedAt):"sem sincronizacao","muted")}
          </div>
        </div>
        <div class="hero-signals">
          <article class="signal-card">
            <p class="label">Modulo ativo</p>
            <strong>${s(n.label)}</strong>
            <p>${s(n.description)}</p>
          </article>
          <article class="signal-card">
            <p class="label">Sincronizacao</p>
            <strong>${s(a.sync.status)}</strong>
            <p>${s(a.sync.lastError||"Leitura operacional sem erro nesta rodada.")}</p>
          </article>
          <article class="signal-card">
            <p class="label">Leitura parcial</p>
            <strong>${s(String(t))}</strong>
            <p>${t>0?"Existe degradacao em pelo menos um read model.":"Todos os read models responderam nesta rodada."}</p>
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

      ${nn(a)}
      ${on(a)}
    </div>
  `}const ke="agua-viva.operacao-web.api-base",Ne="agua-viva.operacao-web.auto-refresh",Le="agua-viva.operacao-web.atendimento-state",rn="http://localhost:8082";function ie(e){try{return window.localStorage.getItem(e)}catch{return null}}function re(e,a){try{window.localStorage.setItem(e,a)}catch{}}function sn(e){const a=ie(e);if(!a)return null;try{return JSON.parse(a)}catch{return null}}function ln(){return ie(ke)||rn}function dn(e){re(ke,e)}function cn(){return ie(Ne)!=="0"}function un(e){re(Ne,e?"1":"0")}function pn(){return sn(Le)}function mn(e){re(Le,JSON.stringify(e))}const gn=15e3,fn=1,Oe=document.querySelector("#app");if(!Oe)throw new Error("Elemento #app nao encontrado.");const se=Oe,ve=ln(),hn=bn()??fn,vn={activeModule:Q(window.location.hash),connection:{apiBase:ve,apiBaseDraft:ve,autoRefresh:cn()},sync:{status:"idle",lastError:null},snapshot:null,atendimento:ze(pn()),despacho:{routeStart:{status:"idle",lastError:null},lastRouteStart:null},entregador:{entregadorId:hn,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}},j=pa(vn),De=ua(e=>{j.setActiveModule(e)});let k;const le=ca({intervalMs:gn,onTick:()=>{k.refreshSnapshot()}});k=la({root:se,router:De,store:j,persistApiBase:dn,persistAutoRefresh:un,persistAtendimentoState:mn});j.subscribe(e=>{je(se,e),le.sync(e.connection.autoRefresh)});k.bind();je(se,j.getState());De.start();le.sync(j.getState().connection.autoRefresh);k.refreshSnapshot();window.addEventListener("beforeunload",()=>{k.dispose(),le.stop()});function bn(){try{const e=new URL(window.location.href),a=Number(e.searchParams.get("entregadorId"));return Number.isInteger(a)&&a>0?a:null}catch{return null}}
