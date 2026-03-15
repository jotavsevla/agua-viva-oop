(function(){const a=document.createElement("link").relList;if(a&&a.supports&&a.supports("modulepreload"))return;for(const o of document.querySelectorAll('link[rel="modulepreload"]'))n(o);new MutationObserver(o=>{for(const r of o)if(r.type==="childList")for(const s of r.addedNodes)s.tagName==="LINK"&&s.rel==="modulepreload"&&n(s)}).observe(document,{childList:!0,subtree:!0});function t(o){const r={};return o.integrity&&(r.integrity=o.integrity),o.referrerPolicy&&(r.referrerPolicy=o.referrerPolicy),o.crossOrigin==="use-credentials"?r.credentials="include":o.crossOrigin==="anonymous"?r.credentials="omit":r.credentials="same-origin",r}function n(o){if(o.ep)return;o.ep=!0;const r=t(o);fetch(o.href,r)}})();const Pe=["MANUAL","WHATSAPP","BINA_FIXO","TELEFONIA_FIXO"],Ne=["NAO_INFORMADO","DINHEIRO","PIX","CARTAO","VALE"],je=["ASAP","HARD","FLEXIVEL"],ra=new Set(["ENTREGUE","CANCELADO"]),Z=new Set(["WHATSAPP","BINA_FIXO","TELEFONIA_FIXO"]);function m(e){return String(e??"").trim()}function ne(e){return m(e).toUpperCase()}function fe(e,a){const t=Number(e);if(!Number.isInteger(t)||t<=0)throw new Error(`${a} invalido`);return t}function he(e,a){const t=m(e).replace(",",".");if(!t)throw new Error(`${a} invalida`);const n=Number(t);if(!Number.isFinite(n))throw new Error(`${a} invalida`);return n}function oe(e){const a=ne(e);if(!a)return"";if(!Pe.includes(a))throw new Error("origemCanal invalido");return a}function re(e){const a=ne(e);if(!a)return"";if(!Ne.includes(a))throw new Error("metodoPagamento invalido");return a}function ie(e){const a=ne(e);if(!a)return"";if(a==="FLEX")return"FLEXIVEL";if(!je.includes(a))throw new Error("janelaTipo invalido");return a}function Te(){return{telefone:"",quantidadeGaloes:"1",atendenteId:"1",origemCanal:"MANUAL",sourceEventId:"",manualRequestId:H(),externalCallId:"",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",latitude:"",longitude:""}}function ia(e){const a=k(e?.draft),t=da(e?.sessionCases),n=e?.activeCaseId??null;return{draft:a,lookupPhone:m(e?.lookupPhone),lookupPedidoId:m(e?.lookupPedidoId),sessionCases:t,activeCaseId:t.some(o=>o.pedidoId===n)?n:null}}function sa(e){return{...ia(e),submitting:!1,syncingCaseId:null,lastError:null,lastSuccess:null}}function k(e){const a=Te(),t={...a,...e,telefone:m(e?.telefone),quantidadeGaloes:m(e?.quantidadeGaloes)||a.quantidadeGaloes,atendenteId:m(e?.atendenteId)||a.atendenteId,origemCanal:oe(m(e?.origemCanal))||a.origemCanal,sourceEventId:m(e?.sourceEventId),manualRequestId:m(e?.manualRequestId),externalCallId:m(e?.externalCallId),metodoPagamento:re(m(e?.metodoPagamento))||a.metodoPagamento,janelaTipo:ie(m(e?.janelaTipo))||a.janelaTipo,janelaInicio:m(e?.janelaInicio),janelaFim:m(e?.janelaFim),nomeCliente:m(e?.nomeCliente),endereco:m(e?.endereco),latitude:m(e?.latitude),longitude:m(e?.longitude)};return t.origemCanal==="MANUAL"&&!t.manualRequestId&&(t.manualRequestId=H()),t}function da(e){return Array.isArray(e)?e.filter(a=>!!(a&&Number.isInteger(a.pedidoId))).map(a=>({pedidoId:a.pedidoId,clienteId:Number.isInteger(a.clienteId)?a.clienteId:null,telefone:m(a.telefone),telefoneNormalizado:_(a.telefoneNormalizado||a.telefone),quantidadeGaloes:Number.isInteger(a.quantidadeGaloes)?a.quantidadeGaloes:0,atendenteId:Number.isInteger(a.atendenteId)?a.atendenteId:0,origemCanal:oe(a.origemCanal)||"MANUAL",metodoPagamento:re(a.metodoPagamento)||"NAO_INFORMADO",janelaTipo:ie(a.janelaTipo)||"ASAP",janelaInicio:m(a.janelaInicio),janelaFim:m(a.janelaFim),nomeCliente:m(a.nomeCliente),endereco:m(a.endereco),requestKey:m(a.requestKey)||null,clienteCriado:!!a.clienteCriado,idempotente:!!a.idempotente,statusAtual:m(a.statusAtual)||null,timeline:a.timeline||null,execucao:a.execucao||null,saldo:a.saldo||null,extrato:a.extrato||null,financeStatus:la(a.financeStatus),notes:Array.isArray(a.notes)?a.notes.map(t=>m(t)).filter(Boolean):[],error:m(a.error)||null,createdAt:m(a.createdAt)||new Date().toISOString(),updatedAt:m(a.updatedAt)||m(a.createdAt)||new Date().toISOString(),lastSyncAt:m(a.lastSyncAt)||null})).sort(ee):[]}function la(e){return e==="ok"||e==="unavailable"||e==="error"?e:"idle"}function H(){const e=new Date().toISOString().replace(/[-:.TZ]/g,"").slice(0,14),a=Math.random().toString(36).slice(2,8);return`manual-ui-${e}-${a}`}function ve(e){const a=k({telefone:m(e.get("telefone")),quantidadeGaloes:m(e.get("quantidadeGaloes")),atendenteId:m(e.get("atendenteId")),origemCanal:oe(m(e.get("origemCanal"))),sourceEventId:m(e.get("sourceEventId")),manualRequestId:m(e.get("manualRequestId")),externalCallId:m(e.get("externalCallId")),metodoPagamento:re(m(e.get("metodoPagamento"))),janelaTipo:ie(m(e.get("janelaTipo"))),janelaInicio:m(e.get("janelaInicio")),janelaFim:m(e.get("janelaFim")),nomeCliente:m(e.get("nomeCliente")),endereco:m(e.get("endereco")),latitude:m(e.get("latitude")),longitude:m(e.get("longitude"))});return a.origemCanal==="MANUAL"&&!a.manualRequestId&&(a.manualRequestId=H()),a}function se(e){const a=k(e),t={telefone:m(a.telefone),quantidadeGaloes:fe(a.quantidadeGaloes,"quantidadeGaloes"),atendenteId:fe(a.atendenteId,"atendenteId")};if(!t.telefone)throw new Error("telefone obrigatorio");a.origemCanal&&(t.origemCanal=a.origemCanal),a.metodoPagamento&&(t.metodoPagamento=a.metodoPagamento),a.janelaTipo&&(t.janelaTipo=a.janelaTipo),a.sourceEventId&&(t.sourceEventId=a.sourceEventId),a.manualRequestId&&(t.manualRequestId=a.manualRequestId),a.externalCallId&&(t.externalCallId=a.externalCallId),a.janelaInicio&&(t.janelaInicio=a.janelaInicio),a.janelaFim&&(t.janelaFim=a.janelaFim),a.nomeCliente&&(t.nomeCliente=a.nomeCliente),a.endereco&&(t.endereco=a.endereco);const n=!!a.latitude,o=!!a.longitude;if(n!==o)throw new Error("latitude e longitude devem ser informadas juntas");return n&&o&&(t.latitude=he(a.latitude,"latitude"),t.longitude=he(a.longitude,"longitude")),ca(t),ua(t),{payload:t,idempotencyKey:pa(t)}}function ca(e){const a=e.origemCanal||"";if(a==="MANUAL"&&e.sourceEventId)throw new Error("sourceEventId nao pode ser usado com origemCanal=MANUAL");if(Z.has(a)&&!e.sourceEventId)throw new Error(`sourceEventId obrigatorio para origemCanal=${a}`);if(Z.has(a)&&e.manualRequestId)throw new Error(`manualRequestId so pode ser usado com origemCanal=MANUAL (recebido: ${a})`);if(!a&&e.sourceEventId&&e.manualRequestId)throw new Error("manualRequestId nao pode ser combinado com sourceEventId quando origemCanal estiver vazio");if(e.sourceEventId&&e.externalCallId&&e.sourceEventId!==e.externalCallId)throw new Error("sourceEventId diverge de externalCallId");if(e.manualRequestId&&e.externalCallId&&e.manualRequestId!==e.externalCallId)throw new Error("manualRequestId diverge de externalCallId")}function ua(e){if(e.janelaTipo==="HARD"){if(!e.janelaInicio||!e.janelaFim)throw new Error("janelaTipo=HARD exige janelaInicio e janelaFim");return}if(e.janelaInicio||e.janelaFim)throw new Error("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD")}function pa(e){return e.origemCanal==="MANUAL"?e.manualRequestId||e.externalCallId||null:Z.has(e.origemCanal||"")?e.sourceEventId||e.externalCallId||null:e.sourceEventId||e.manualRequestId||e.externalCallId||null}function De(e,a){const t=[];try{se(e)}catch(o){t.push(o instanceof Error?o.message:"payload invalido")}const n=_(e.telefone);if(n){const o=a.find(r=>r.telefoneNormalizado===n&&!va(r));o&&t.push(`Ja existe pedido em acompanhamento na sessao para este telefone (#${o.pedidoId}, ${o.statusAtual||"sem status"}).`)}return G(t)}function ma(e){const a=[];return e.metodoPagamento==="VALE"&&a.push("Checkout em vale depende de saldo suficiente no cliente e pode ser bloqueado pelo backend."),e.origemCanal==="MANUAL"&&!e.nomeCliente&&a.push("Sem nome do cliente, o pedido entra, mas o atendimento perde contexto para o despacho."),e.janelaTipo==="HARD"&&a.push("Pedidos HARD devem entrar com horario fechado para evitar retrabalho no despacho."),G(a)}function _(e){return String(e??"").replace(/\D/g,"")}function ke(e){const a=_(e);return a.length===11?`(${a.slice(0,2)}) ${a.slice(2,7)}-${a.slice(7)}`:a.length===10?`(${a.slice(0,2)}) ${a.slice(2,6)}-${a.slice(6)}`:m(e)}function ga(e,a){const t=k(e),n=new Date().toISOString(),{payload:o,idempotencyKey:r}=se(t);return{pedidoId:a.pedidoId,clienteId:a.clienteId,telefone:o.telefone,telefoneNormalizado:a.telefoneNormalizado,quantidadeGaloes:o.quantidadeGaloes,atendenteId:o.atendenteId,origemCanal:o.origemCanal||"MANUAL",metodoPagamento:o.metodoPagamento||"NAO_INFORMADO",janelaTipo:o.janelaTipo||"ASAP",janelaInicio:o.janelaInicio||"",janelaFim:o.janelaFim||"",nomeCliente:o.nomeCliente||"",endereco:o.endereco||"",requestKey:r,clienteCriado:a.clienteCriado,idempotente:a.idempotente,statusAtual:"PENDENTE",timeline:null,execucao:null,saldo:null,extrato:null,financeStatus:"idle",notes:[],error:null,createdAt:n,updatedAt:n,lastSyncAt:null}}function $e(e,a){const t=a.timeline===void 0?e.timeline:a.timeline,n=a.execucao===void 0?e.execucao:a.execucao;return{...e,timeline:t,execucao:n,saldo:a.saldo===void 0?e.saldo:a.saldo,extrato:a.extrato===void 0?e.extrato:a.extrato,financeStatus:a.financeStatus||e.financeStatus,statusAtual:t?.statusAtual||n?.statusPedido||e.statusAtual,error:a.error===void 0?e.error:a.error,notes:a.notes?G([...e.notes,...a.notes]):e.notes,updatedAt:new Date().toISOString(),lastSyncAt:new Date().toISOString()}}function fa(e){const a=k(e);return{...Te(),quantidadeGaloes:a.quantidadeGaloes,atendenteId:a.atendenteId,origemCanal:"MANUAL",metodoPagamento:a.metodoPagamento,janelaTipo:a.janelaTipo,janelaInicio:a.janelaTipo==="HARD"?a.janelaInicio:"",janelaFim:a.janelaTipo==="HARD"?a.janelaFim:"",manualRequestId:H()}}function Q(e,a){const t=e.findIndex(o=>o.pedidoId===a.pedidoId);if(t===-1)return[...e,a].sort(ee);const n=[...e];return n[t]={...n[t],...a,notes:G([...n[t].notes||[],...a.notes||[]])},n.sort(ee)}function ha(e){return e.sessionCases.find(a=>a.pedidoId===e.activeCaseId)||null}function Le(e,a){const t=_(a);return t?e.sessionCases.filter(n=>n.telefoneNormalizado.includes(t)):e.sessionCases}function va(e){return ra.has(String(e.statusAtual||"").toUpperCase())}function Oe(e){return e.janelaTipo==="HARD"?e.janelaInicio&&e.janelaFim?`HARD ${e.janelaInicio}-${e.janelaFim}`:"HARD incompleta":e.janelaTipo==="FLEXIVEL"?"Flexivel":"ASAP"}function $a(e){if(!e)return{tone:"muted",stage:"Sem pedido em foco",detail:"Crie um novo pedido ou selecione um atendimento da sessao para montar o handoff.",action:"Nada para repassar ao despacho por enquanto."};const a=String(e.statusAtual||"").toUpperCase(),t=String(e.execucao?.camada||"").toUpperCase();return a==="CANCELADO"?{tone:"danger",stage:"Atendimento interrompido",detail:"O pedido foi cancelado e nao deve seguir para a operacao sem nova triagem.",action:"Registrar motivo com clareza e orientar novo contato se necessario."}:a==="ENTREGUE"?{tone:"ok",stage:"Ciclo encerrado",detail:"O pedido ja foi concluido em campo.",action:"Encerrar o caso no atendimento e manter apenas rastreabilidade."}:t==="PRIMARIA_EM_EXECUCAO"?{tone:"info",stage:"Em execucao",detail:"O pedido ja esta com a operacao de entrega, com rota primaria ativa.",action:"Atendimento acompanha apenas excecoes; despacho ja recebeu o handoff."}:t==="SECUNDARIA_CONFIRMADA"?{tone:"warn",stage:"Aguardando inicio de rota",detail:"O pedido foi encaixado na secundaria e depende de acao do despacho para avancar.",action:"Avisar o despacho sobre rota pronta e janela do cliente."}:a==="CONFIRMADO"?{tone:"warn",stage:"Confirmado para despacho",detail:"O pedido ja saiu do atendimento, mas ainda precisa ser roteirizado.",action:"Repassar prioridade, forma de pagamento e observacoes do cadastro."}:{tone:"info",stage:"Entrou na fila",detail:"Pedido registrado e aguardando a cadencia normal do despacho.",action:"Checar o cockpit operacional para acompanhar a entrada nas filas."}}function ba(e){const a=new Date().toISOString();return{pedidoId:e,clienteId:null,telefone:"",telefoneNormalizado:"",quantidadeGaloes:0,atendenteId:0,origemCanal:"MANUAL",metodoPagamento:"NAO_INFORMADO",janelaTipo:"ASAP",janelaInicio:"",janelaFim:"",nomeCliente:"",endereco:"",requestKey:null,clienteCriado:!1,idempotente:!1,statusAtual:null,timeline:null,execucao:null,saldo:null,extrato:null,financeStatus:"idle",notes:["Pedido importado por consulta manual."],error:null,createdAt:a,updatedAt:a,lastSyncAt:null}}function ee(e,a){return a.updatedAt.localeCompare(e.updatedAt)}function G(e){return[...new Set(e.map(a=>m(a)).filter(Boolean))]}const Aa=20,Ea=8;class qe extends Error{status;detail;constructor(a,t,n=null){super(a),this.name="ApiError",this.status=t,this.detail=n}}function Ia(e,a){return`${String(e).replace(/\/+$/,"")}${a}`}async function R(e,a,t={}){const n=await fetch(Ia(e,a),{method:t.method||"GET",headers:{...t.body?{"Content-Type":"application/json"}:{},...t.headers||{}},body:t.body?JSON.stringify(t.body):void 0}),o=await n.json().catch(()=>({}));if(!n.ok)throw new qe(o.erro||o.message||`HTTP ${n.status}`,n.status,o.detalhe||null);return o}function ya(){return{health:"unknown",painel:"unknown",eventos:"unknown",mapa:"unknown"}}async function Sa(e){const a=ya(),t=[],[n,o,r,s]=await Promise.allSettled([R(e,"/health"),R(e,"/api/operacao/painel"),R(e,`/api/operacao/eventos?limite=${Aa}`),R(e,"/api/operacao/mapa")]),A=M(n,"health",a,t),b=M(o,"painel",a,t),E=M(r,"eventos",a,t),$=M(s,"mapa",a,t);if(!A&&!b&&!E&&!$)throw new Error("Nao foi possivel carregar nenhum read model operacional.");return{health:A,painel:b,eventos:E,mapa:$,readiness:a,partialErrors:t,fetchedAt:new Date().toISOString()}}function M(e,a,t,n){return e.status==="fulfilled"?(t[a]="ok",e.value):(t[a]="error",n.push(`${a}: ${e.reason instanceof Error?e.reason.message:"falha"}`),null)}function be(e){return e instanceof qe&&[400,404,405,501].includes(e.status)}async function Ca(e,a,t){return R(e,"/api/atendimento/pedidos",{method:"POST",headers:t?{"Idempotency-Key":t}:void 0,body:a})}async function Ra(e,a){return R(e,`/api/pedidos/${a}/timeline`)}async function xa(e,a){return R(e,`/api/pedidos/${a}/execucao`)}async function Pa(e,a){return R(e,`/api/financeiro/clientes/${a}/saldo`)}async function Na(e,a,t=Ea){return R(e,`/api/financeiro/clientes/${a}/extrato?limit=${t}`)}async function ja(e,a){return R(e,`/api/entregadores/${a}/roteiro`)}async function Ae(e,a){return R(e,"/api/operacao/rotas/prontas/iniciar",{method:"POST",body:{entregadorId:a}})}async function Ta(e,a){return R(e,"/api/eventos",{method:"POST",body:a})}function we(e,a){const t=new URL(a);return t.searchParams.set("entregadorId",String(e)),t.hash="#/entregador",t.toString()}function Da(e){const a=e?.paradasPendentesExecucao??[],t=e?.paradasConcluidas??[],n=a.length+t.length;return{totalParadas:n,pendentes:a.length,concluidas:t.length,percentualConcluido:n===0?0:Math.round(t.length/n*100),proximaParada:a[0]??null}}function ka(e,a,t){return`operacao-web-${a}-${e.toLowerCase()}-${t}-${Date.now()}`}function Ee(e,a,t){return a.idempotente?{tone:"warn",title:`${e} ja reconhecido`,detail:t,payload:a}:{tone:"ok",title:`${e} confirmado`,detail:t,payload:a}}function C(e){return e instanceof Error?e.message:"Falha ao sincronizar a operacao."}function La(e){return e==="PEDIDO_ENTREGUE"||e==="PEDIDO_FALHOU"||e==="PEDIDO_CANCELADO"?e:null}function Oa(e){const{root:a,router:t,store:n,persistApiBase:o,persistAutoRefresh:r,persistAtendimentoState:s,persistEntregadorId:A}=e,b=e.targetWindow??window,E=()=>{const l=n.getState().atendimento;s({draft:l.draft,lookupPhone:l.lookupPhone,lookupPedidoId:l.lookupPedidoId,sessionCases:l.sessionCases,activeCaseId:l.activeCaseId})},$=(l,u=!0)=>{n.updateAtendimento(l,{notify:u}),E()},S=l=>{const u=new URL(b.location.href);u.searchParams.set("entregadorId",String(l)),b.history.replaceState(null,"",u.toString())},d=()=>{const l=a.querySelector("#entregador-id-input"),u=Number(l?.value);return Number.isInteger(u)&&u>0?u:null},I=async()=>{if(n.getState().sync.status!=="loading"){n.startSync();try{const l=await Sa(n.getState().connection.apiBase);n.finishSync(l)}catch(l){n.failSync(C(l))}}},j=async()=>{if(n.getState().entregador.sync.status!=="loading"){n.startEntregadorSync();try{const{apiBase:l}=n.getState().connection,{entregadorId:u}=n.getState().entregador,c=await ja(l,u);n.finishEntregadorSync(c,new Date().toISOString())}catch(l){n.failEntregadorSync(C(l))}}},q=async l=>{if(!Number.isInteger(l)||l<=0)return;let u=n.getState().atendimento.sessionCases.find(c=>c.pedidoId===l);if(u)$(c=>({...c,activeCaseId:l,lastError:null}));else{const c=ba(l);u=c,$(h=>({...h,sessionCases:Q(h.sessionCases,c),activeCaseId:l,lastError:null}))}$(c=>({...c,syncingCaseId:l}));try{const c=n.getState().atendimento.sessionCases.find(U=>U.pedidoId===l);if(!c)throw new Error(`Pedido #${l} nao encontrado na sessao de atendimento.`);const[h,p,v,x]=await Promise.allSettled([Ra(n.getState().connection.apiBase,l),xa(n.getState().connection.apiBase,l),c.clienteId?Pa(n.getState().connection.apiBase,c.clienteId):Promise.resolve(null),c.clienteId?Na(n.getState().connection.apiBase,c.clienteId):Promise.resolve(null)]),T=[];let N=c.financeStatus,K=null;const W=$e(c,{timeline:h.status==="fulfilled"?h.value:c.timeline,execucao:p.status==="fulfilled"?p.value:c.execucao});h.status==="rejected"&&T.push(C(h.reason)),p.status==="rejected"&&T.push(C(p.reason)),v.status==="fulfilled"&&v.value?N="ok":v.status==="rejected"&&(be(v.reason)?(N="unavailable",T.push("Saldo/extrato nao estao disponiveis nesta base da app.")):(N="error",K=C(v.reason))),x.status==="fulfilled"&&x.value?N="ok":x.status==="rejected"&&(be(x.reason)?N="unavailable":N!=="unavailable"&&(N="error",K=C(x.reason)));const oa=$e(W,{saldo:v.status==="fulfilled"?v.value:W.saldo,extrato:x.status==="fulfilled"?x.value:W.extrato,financeStatus:N,error:K,notes:T});$(U=>({...U,syncingCaseId:null,lastSuccess:`Pedido #${l} sincronizado com timeline e execucao.`,sessionCases:Q(U.sessionCases,oa)}))}catch(c){$(h=>({...h,syncingCaseId:null,lastError:C(c)}))}},Qe=async l=>{const u=ve(new FormData(l));$(h=>({...h,draft:u,lastError:null,lastSuccess:null}));let c;try{c=se(u)}catch(h){$(p=>({...p,lastError:C(h)}));return}$(h=>({...h,submitting:!0}));try{const h=await Ca(n.getState().connection.apiBase,c.payload,c.idempotencyKey),p=ga(u,h);$(v=>({...v,submitting:!1,draft:fa(v.draft),activeCaseId:h.pedidoId,lastSuccess:`Pedido #${h.pedidoId} criado para cliente ${h.clienteId}.`,sessionCases:Q(v.sessionCases,p)})),await q(h.pedidoId),await I()}catch(h){$(p=>({...p,submitting:!1,lastError:C(h)}))}},Ye=async l=>{const u=new FormData(l),c=String(u.get("lookupPhone")||"").trim(),h=Number(String(u.get("lookupPedidoId")||"").trim());if($(v=>({...v,lookupPhone:c,lookupPedidoId:Number.isInteger(h)&&h>0?String(h):"",lastError:null})),h>0){await q(h);return}const p=Le(n.getState().atendimento,c);if(p.length===0){$(v=>({...v,lastError:"Nenhum atendimento da sessao corresponde a esse telefone."}));return}$(v=>({...v,activeCaseId:p[0].pedidoId}))},w=async()=>{if(n.getState().activeModule==="entregador"){await j();return}await I(),n.getState().activeModule==="atendimento"&&n.getState().atendimento.activeCaseId&&await q(n.getState().atendimento.activeCaseId)},Ze=async l=>{if(b.confirm(`Iniciar a proxima rota pronta do entregador ${l}?`)){n.startDespachoRouteStart();try{const c=await Ae(n.getState().connection.apiBase,l);n.finishDespachoRouteStart(c),await I()}catch(c){n.failDespachoRouteStart(C(c))}}},ea=(l,u)=>{if(l==="PEDIDO_ENTREGUE")return b.confirm(`Confirmar entrega da entrega ${u}?`)?{}:null;const c=l==="PEDIDO_FALHOU"?"Motivo da falha":"Motivo do cancelamento (obrigatorio para registrar na operacao)",h=b.prompt(c,"");if(h===null)return null;const p=h.trim();if(!p)return n.failEntregadorAction("Informe um motivo para registrar a excecao."),null;if(l!=="PEDIDO_CANCELADO")return{motivo:p};const v=b.prompt("Cobranca de cancelamento em centavos (opcional)","");if(!v||!v.trim())return{motivo:p};const x=Number(v);return!Number.isInteger(x)||x<0?(n.failEntregadorAction("Cobranca de cancelamento invalida."),null):{motivo:p,cobrancaCancelamentoCentavos:x}},aa=async(l,u)=>{const{entregadorId:c}=n.getState().entregador,h=ea(l,u);if(h===null)return;const p={externalEventId:ka(l,c,u),eventType:l,entregaId:u,actorEntregadorId:c,...h};n.startEntregadorAction();try{const v=await Ta(n.getState().connection.apiBase,p);n.finishEntregadorAction(Ee(l,v,v.idempotente?`A API reconheceu que a entrega ${u} ja estava nesse estado final.`:`Entrega ${u} atualizada com sucesso para o evento ${l}.`)),await j()}catch(v){n.failEntregadorAction(C(v))}},ta=async()=>{const l=we(n.getState().entregador.entregadorId,b.location.href);try{if(!b.navigator.clipboard)throw new Error("Clipboard indisponivel neste navegador.");await b.navigator.clipboard.writeText(l),n.finishEntregadorAction({tone:"ok",title:"Link copiado",detail:"Deep link do entregador copiado para compartilhamento rapido.",payload:null})}catch(u){n.failEntregadorAction(C(u))}},na=async()=>{const{entregadorId:l}=n.getState().entregador;if(b.confirm(`Iniciar a proxima rota pronta do entregador ${l}?`)){n.startEntregadorAction();try{const c=await Ae(n.getState().connection.apiBase,l);n.finishEntregadorAction(Ee("ROTA_INICIADA",c,c.idempotente?`A rota ${c.rotaId} ja estava em andamento para o entregador ${l}.`:`Rota ${c.rotaId} iniciada com sucesso para o entregador ${l}.`)),await j()}catch(c){n.failEntregadorAction(C(c))}}},me=l=>{const c=(l.target instanceof HTMLElement?l.target:null)?.closest("[data-action]");if(!c||!a.contains(c))return;switch(c.dataset.action){case"save-api-base":n.commitConnection(),o(n.getState().connection.apiBase),w();return;case"refresh-snapshot":w();return;case"navigate":{const p=c.dataset.moduleId;p&&(t.navigate(p),n.setActiveModule(p),w());return}case"focus-atendimento-case":{const p=Number(c.dataset.pedidoId);if(!Number.isInteger(p)||p<=0)return;$(v=>({...v,activeCaseId:p,lastError:null}));return}case"refresh-atendimento-case":{const p=Number(c.dataset.pedidoId);if(!Number.isInteger(p)||p<=0)return;q(p);return}case"start-prepared-route":{const p=Number(c.dataset.entregadorId);if(!Number.isInteger(p)||p<=0){n.failDespachoRouteStart("Entregador invalido para iniciar a rota.");return}Ze(p);return}case"load-entregador":{const p=d();if(!p){n.failEntregadorSync("Informe um entregadorId valido para carregar o roteiro.");return}n.setEntregadorId(p),A(p),S(p),t.navigate("entregador"),n.setActiveModule("entregador"),j();return}case"copy-entregador-link":ta();return;case"start-entregador-route":na();return;case"run-entregador-event":{const p=La(c.dataset.eventType),v=Number(c.dataset.entregaId);if(!p||!Number.isInteger(v)||v<=0){n.failEntregadorAction("Acao de parada invalida.");return}aa(p,v);return}default:return}},F=l=>{const u=l.target;if(!(u instanceof HTMLInputElement||u instanceof HTMLSelectElement||u instanceof HTMLTextAreaElement)||!a.contains(u))return;if(u instanceof HTMLInputElement&&u.id==="api-base-input"){n.setConnectionDraft(u.value);return}if(u instanceof HTMLInputElement&&u.id==="auto-refresh-input"){const p=!!u.checked;n.setAutoRefresh(p),r(p);return}const c=u.closest("form"),h=l.type==="change";if(c?.id==="atendimento-form"){const p=ve(new FormData(c));$(v=>({...v,draft:p}),h);return}if(c?.id==="atendimento-lookup-form"){const p=new FormData(c);$(v=>({...v,lookupPhone:String(p.get("lookupPhone")||"").trim(),lookupPedidoId:String(p.get("lookupPedidoId")||"").trim()}),h)}},ge=l=>{const u=l.target;if(!(!(u instanceof HTMLFormElement)||!a.contains(u))){if(u.id==="atendimento-form"){l.preventDefault(),Qe(u);return}u.id==="atendimento-lookup-form"&&(l.preventDefault(),Ye(u))}};return{bind(){a.addEventListener("click",me),a.addEventListener("input",F),a.addEventListener("change",F),a.addEventListener("submit",ge)},refreshSnapshot:w,dispose(){a.removeEventListener("click",me),a.removeEventListener("input",F),a.removeEventListener("change",F),a.removeEventListener("submit",ge),t.dispose()}}}const de=[{id:"cockpit",hash:"#/cockpit",label:"Cockpit",description:"Visao consolidada da operacao, dos alertas e dos read models.",status:"active"},{id:"atendimento",hash:"#/atendimento",label:"Atendimento",description:"Busca de cliente na sessao, criacao segura de pedido e handoff conectado ao despacho.",status:"active"},{id:"despacho",hash:"#/despacho",label:"Despacho",description:"Cockpit principal para fila operacional, camadas de frota, risco e acoes de saida.",status:"active"},{id:"entregador",hash:"#/entregador",label:"Entregador",description:"Base reservada para roteiro, progresso de rota, deep link e eventos terminais.",status:"active"}];function le(e){const a=de.find(t=>t.id===e);if(!a)throw new Error(`Modulo desconhecido: ${e}`);return a}function Ie(e){return le(e).hash}function qa(e){return de.some(a=>a.id===e)}function ae(e){const a=String(e||"").replace(/^#\/?/,"").trim().toLowerCase();return qa(a)?a:"cockpit"}function wa(e){const a=e.targetWindow??window;let t=null;const n=()=>{t!==null&&(a.clearInterval(t),t=null)},o=()=>{n(),t=a.setInterval(e.onTick,e.intervalMs)};return{sync(r){if(!r){n();return}o()},stop(){n()}}}function Fa(e,a=window){let t=ae(a.location.hash);const n=()=>{const o=ae(a.location.hash);o!==t&&(t=o,e(o))};return{getCurrentModule(){return t},navigate(o){t=o;const r=Ie(o);a.location.hash!==r&&(a.location.hash=r)},start(){a.addEventListener("hashchange",n);const o=Ie(t);a.location.hash!==o&&(a.location.hash=o)},dispose(){a.removeEventListener("hashchange",n)}}}function Ua(e){let a=e;const t=new Set,n=()=>{t.forEach(r=>r(a))},o=(r,s={})=>{a=r(a),s.notify!==!1&&n()};return{getState(){return a},subscribe(r){return t.add(r),()=>{t.delete(r)}},setConnectionDraft(r){o(s=>({...s,connection:{...s.connection,apiBaseDraft:r}}),{notify:!1})},commitConnection(){o(r=>{const s=r.connection.apiBaseDraft.trim()||r.connection.apiBase;return{...r,connection:{...r.connection,apiBase:s,apiBaseDraft:s}}})},setAutoRefresh(r){o(s=>({...s,connection:{...s.connection,autoRefresh:r}}))},setActiveModule(r){o(s=>({...s,activeModule:r}))},updateAtendimento(r,s){o(A=>({...A,atendimento:r(A.atendimento)}),s)},setEntregadorId(r){o(s=>({...s,entregador:{...s.entregador,entregadorId:r,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}}))},startSync(){o(r=>({...r,sync:{...r.sync,status:"loading",lastError:null}}))},finishSync(r){o(s=>({...s,snapshot:r,sync:{status:"ready",lastError:null}}))},failSync(r){o(s=>({...s,sync:{status:"error",lastError:r}}))},startDespachoRouteStart(){o(r=>({...r,despacho:{...r.despacho,routeStart:{status:"loading",lastError:null},lastRouteStart:null}}))},finishDespachoRouteStart(r){o(s=>({...s,despacho:{routeStart:{status:"ready",lastError:null},lastRouteStart:{tone:r.idempotente?"warn":"ok",title:r.idempotente?"Acao reconhecida como idempotente":"Rota pronta iniciada",detail:`R${r.rotaId} disparada para o pedido ${r.pedidoId} e entrega ${r.entregaId}.`,payload:r}}}))},failDespachoRouteStart(r){o(s=>({...s,despacho:{routeStart:{status:"error",lastError:r},lastRouteStart:{tone:"danger",title:"Falha ao iniciar rota pronta",detail:r,payload:null}}}))},startEntregadorSync(){o(r=>({...r,entregador:{...r.entregador,sync:{status:"loading",lastError:null}}}))},finishEntregadorSync(r,s){o(A=>({...A,entregador:{...A.entregador,roteiro:r,fetchedAt:s,sync:{status:"ready",lastError:null}}}))},failEntregadorSync(r){o(s=>({...s,entregador:{...s.entregador,sync:{status:"error",lastError:r}}}))},startEntregadorAction(){o(r=>({...r,entregador:{...r.entregador,action:{status:"loading",lastError:null}}}))},finishEntregadorAction(r){o(s=>({...s,entregador:{...s.entregador,action:{status:"ready",lastError:null},lastAction:r}}))},failEntregadorAction(r){o(s=>({...s,entregador:{...s.entregador,action:{status:"error",lastError:r},lastAction:{tone:"danger",title:"Acao rejeitada",detail:r,payload:null}}}))}}}function g(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function J(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function y(e,a){return`<span class="pill ${a}">${g(e)}</span>`}function Y(e,a,t){return`<option value="${g(e)}" ${e===t?"selected":""}>${g(a)}</option>`}function ce(e){const a=String(e.statusAtual||"").toUpperCase();return a==="ENTREGUE"?"ok":a==="CANCELADO"?"danger":a==="CONFIRMADO"?"warn":a==="EM_ROTA"?"info":"muted"}function Ma(e){const a=e.snapshot?.painel;return`
    <div class="atendimento-pulse-grid">
      ${[{label:"Fila nova",value:String(a?.filas.pendentesElegiveis.length??"-"),copy:"Pedidos que ainda vao entrar em triagem."},{label:"Rotas prontas",value:String(a?.rotas.planejadas.length??"-"),copy:"Demandas ja comprometidas aguardando acao do despacho."},{label:"Casos na sessao",value:String(e.atendimento.sessionCases.length),copy:"Atendimentos recentes preservados localmente para handoff."},{label:"Ultima leitura",value:e.snapshot?.fetchedAt?J(e.snapshot.fetchedAt):"-",copy:"Base operacional usada como pano de fundo do atendimento."}].map(n=>`
            <article class="pulse-card atendimento-pulse-card">
              <p class="label">${g(n.label)}</p>
              <strong>${g(n.value)}</strong>
              <p>${g(n.copy)}</p>
            </article>
          `).join("")}
    </div>
  `}function za(e){const a=De(e.atendimento.draft,e.atendimento.sessionCases),t=ma(e.atendimento.draft),n=[];return a.length>0&&n.push(`
      <div class="notice notice-danger">
        <strong>Bloqueios antes de enviar:</strong> ${g(a.join(" | "))}
      </div>
    `),t.length>0&&n.push(`
      <div class="notice notice-warn">
        <strong>Pontos de atencao:</strong> ${g(t.join(" | "))}
      </div>
    `),e.atendimento.lastError&&n.push(`
      <div class="notice notice-danger">
        <strong>Falha no atendimento:</strong> ${g(e.atendimento.lastError)}
      </div>
    `),e.atendimento.lastSuccess&&n.push(`
      <div class="notice notice-ok">
        <strong>Atendimento registrado:</strong> ${g(e.atendimento.lastSuccess)}
      </div>
    `),e.snapshot?.partialErrors&&e.snapshot.partialErrors.length>0&&n.push(`
      <div class="notice notice-warn">
        <strong>Leitura operacional parcial:</strong> ${g(e.snapshot.partialErrors.join(" | "))}
      </div>
    `),n.join("")}function Ba(e){const a=e.atendimento.draft,t=De(a,e.atendimento.sessionCases),n=a.janelaTipo==="HARD",o=a.origemCanal==="MANUAL",r=a.origemCanal==="WHATSAPP"||a.origemCanal==="BINA_FIXO"||a.origemCanal==="TELEFONIA_FIXO";return`
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
            <input name="telefone" type="tel" value="${g(a.telefone)}" placeholder="(38) 99876-1234" required />
          </label>
          <label class="field">
            <span>Quantidade de galoes</span>
            <input name="quantidadeGaloes" type="number" min="1" step="1" value="${g(a.quantidadeGaloes)}" required />
          </label>
          <label class="field">
            <span>Atendente ID</span>
            <input name="atendenteId" type="number" min="1" step="1" value="${g(a.atendenteId)}" required />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Metodo de pagamento</span>
            <select name="metodoPagamento">
              ${Ne.map(s=>Y(s,s.replace("_"," "),a.metodoPagamento)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Janela</span>
            <select name="janelaTipo">
              ${je.map(s=>Y(s,s,a.janelaTipo)).join("")}
            </select>
          </label>
          <label class="field">
            <span>Origem do canal</span>
            <select name="origemCanal">
              ${Pe.map(s=>Y(s,s.replace("_"," "),a.origemCanal)).join("")}
            </select>
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${n?"":"field-disabled"}">
            <span>Janela inicio</span>
            <input name="janelaInicio" type="time" value="${g(a.janelaInicio)}" ${n?"":"disabled"} />
          </label>
          <label class="field ${n?"":"field-disabled"}">
            <span>Janela fim</span>
            <input name="janelaFim" type="time" value="${g(a.janelaFim)}" ${n?"":"disabled"} />
          </label>
          <label class="field">
            <span>Nome do cliente</span>
            <input name="nomeCliente" type="text" value="${g(a.nomeCliente)}" placeholder="Condominio Horizonte" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field">
            <span>Endereco</span>
            <input name="endereco" type="text" value="${g(a.endereco)}" placeholder="Rua da Operacao, 99" />
          </label>
          <label class="field">
            <span>Latitude</span>
            <input name="latitude" type="text" value="${g(a.latitude)}" placeholder="-16.7200" />
          </label>
          <label class="field">
            <span>Longitude</span>
            <input name="longitude" type="text" value="${g(a.longitude)}" placeholder="-43.8600" />
          </label>
        </div>

        <div class="form-grid">
          <label class="field ${r?"":"field-disabled"}">
            <span>sourceEventId</span>
            <input name="sourceEventId" type="text" value="${g(a.sourceEventId)}" placeholder="evt-whatsapp-001" ${r?"":"disabled"} />
          </label>
          <label class="field ${o?"":"field-disabled"}">
            <span>manualRequestId</span>
            <input name="manualRequestId" type="text" value="${g(a.manualRequestId)}" placeholder="manual-ui-..." ${o?"":"disabled"} />
          </label>
          <label class="field">
            <span>externalCallId</span>
            <input name="externalCallId" type="text" value="${g(a.externalCallId)}" placeholder="call-center-001" />
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
  `}function Ha(e){return e.financeStatus==="unavailable"?'<div class="notice notice-warn"><strong>Financeiro nao acoplado:</strong> a base atual nao expoe saldo/extrato do cliente nesta app.</div>':e.financeStatus==="error"?'<div class="notice notice-danger"><strong>Financeiro com falha:</strong> nao foi possivel sincronizar saldo/extrato agora.</div>':!e.saldo&&!e.extrato?'<p class="empty-copy">Saldo e extrato ainda nao sincronizados para este cliente.</p>':`
    <div class="finance-grid">
      <article class="metric-card finance-card">
        <p class="metric-label">Saldo de vales</p>
        <p class="metric-value">${g(e.saldo?.quantidade??"-")}</p>
      </article>
      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Extrato recente</h3>
          ${y(e.extrato?String(e.extrato.itens.length):"0","info")}
        </div>
        <div class="timeline-list">
          ${e.extrato&&e.extrato.itens.length>0?e.extrato.itens.slice(0,5).map(_a).join(""):'<p class="empty-copy">Nenhum movimento recente retornado.</p>'}
        </div>
      </section>
    </div>
  `}function _a(e){return`
    <article class="timeline-item compact">
      <div class="timeline-topline">
        <strong>${g(e.tipo)}</strong>
        ${y(`saldo ${e.saldoApos}`,e.tipo==="CREDITO"?"ok":"warn")}
      </div>
      <p class="mono">${g(e.quantidade)} galao(oes) · ${g(J(e.data))}</p>
      <p class="empty-copy">${g(e.observacao||e.registradoPor)}</p>
    </article>
  `}function Ga(e){return`
    <section class="timeline-shell">
      <div class="context-block-header">
        <h3>Timeline do pedido</h3>
        ${y(e.statusAtual||"sem status",ce(e))}
      </div>
      <div class="timeline-list">
        ${e.timeline&&e.timeline.eventos.length>0?e.timeline.eventos.map(a=>`
                    <article class="timeline-item">
                      <div class="timeline-topline">
                        <strong>${g(a.deStatus)} → ${g(a.paraStatus)}</strong>
                        ${y(a.origem,"muted")}
                      </div>
                      <p class="mono">${g(J(a.timestamp))}</p>
                      ${a.observacao?`<p>${g(a.observacao)}</p>`:""}
                    </article>
                  `).join(""):'<p class="empty-copy">Timeline ainda nao carregada para este pedido.</p>'}
      </div>
    </section>
  `}function Ja(e){const a=ha(e.atendimento),t=Le(e.atendimento,e.atendimento.lookupPhone),n=$a(a),o=a?Ha(a):'<p class="empty-copy">Selecione um atendimento para ver contexto financeiro e historico.</p>',r=a?Ga(a):'<p class="empty-copy">Nenhum atendimento em foco.</p>',s=a&&e.atendimento.syncingCaseId===a.pedidoId;return`
    <section class="panel atendimento-context-panel">
      <div class="panel-header">
        <div>
          <h2>Contexto e handoff</h2>
          <p class="section-copy">Consulta rapida por pedido, ressincronizacao com a API e passagem clara para o despacho.</p>
        </div>
        ${y(a?`pedido #${a.pedidoId}`:"sem foco",a?ce(a):"muted")}
      </div>

      <form id="atendimento-lookup-form" class="lookup-form">
        <label class="field">
          <span>Buscar por telefone da sessao</span>
          <input name="lookupPhone" type="text" value="${g(e.atendimento.lookupPhone)}" placeholder="38998761234" />
        </label>
        <label class="field">
          <span>Consultar pedidoId na API</span>
          <input name="lookupPedidoId" type="number" min="1" value="${g(e.atendimento.lookupPedidoId)}" placeholder="8421" />
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
                <strong>${g(a.nomeCliente||ke(a.telefone)||`Pedido #${a.pedidoId}`)}</strong>
              </div>
              <p>${g(n.detail)}</p>
              <p class="priority-action">${g(n.action)}</p>
              <div class="context-metrics">
                <article class="metric-card compact">
                  <p class="metric-label">Pedido</p>
                  <p class="metric-value small">#${g(a.pedidoId)}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Cliente</p>
                  <p class="metric-value small">${g(a.clienteId??"-")}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Janela</p>
                  <p class="metric-value small">${g(Oe(a))}</p>
                </article>
                <article class="metric-card compact">
                  <p class="metric-label">Execucao</p>
                  <p class="metric-value small">${g(a.execucao?.camada||a.statusAtual||"-")}</p>
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
              ${a.error?`<div class="notice notice-danger"><strong>Ultimo erro:</strong> ${g(a.error)}</div>`:""}
              ${a.notes.length>0?`<div class="notice notice-warn"><strong>Notas de contexto:</strong> ${g(a.notes.join(" | "))}</div>`:""}
              <div class="toolbar-actions handoff-actions">
                <button class="button primary" type="button" data-action="navigate" data-module-id="cockpit">Abrir cockpit operacional</button>
              </div>
            </article>
          `:""}

      <div class="context-stack">
        ${r}
        ${o}
      </div>

      <section class="timeline-shell">
        <div class="context-block-header">
          <h3>Atendimentos encontrados</h3>
          ${y(String(t.length),"muted")}
        </div>
        <div class="session-list">
          ${t.length>0?t.map(A=>Va(A,e.atendimento.activeCaseId,e.atendimento.syncingCaseId)).join(""):'<p class="empty-copy">Nenhum atendimento da sessao corresponde a essa busca.</p>'}
        </div>
      </section>
    </section>
  `}function Va(e,a,t){const n=ce(e),o=a===e.pedidoId,r=t===e.pedidoId;return`
    <article class="session-card ${o?"is-active":""}">
      <div class="queue-card-header">
        <strong>${g(e.nomeCliente||ke(e.telefone)||`Pedido #${e.pedidoId}`)}</strong>
        ${y(e.statusAtual||"sem status",n)}
      </div>
      <p class="mono">Pedido #${g(e.pedidoId)} · cliente ${g(e.clienteId??"-")}</p>
      <p class="empty-copy">${g(Oe(e))} · ${g(e.metodoPagamento||"NAO_INFORMADO")}</p>
      <p class="empty-copy">Atualizado ${g(J(e.lastSyncAt||e.updatedAt))}</p>
      <div class="toolbar-actions inline-actions">
        <button class="button secondary" data-action="focus-atendimento-case" data-pedido-id="${e.pedidoId}" type="button">
          ${o?"Em foco":"Trazer para foco"}
        </button>
        <button class="button primary" data-action="refresh-atendimento-case" data-pedido-id="${e.pedidoId}" type="button" ${r?"disabled":""}>
          ${r?"Atualizando...":"Atualizar"}
        </button>
      </div>
    </article>
  `}function Xa(e){return`
    <section class="atendimento-workspace">
      ${Ma(e)}
      ${za(e)}
      <div class="atendimento-grid">
        ${Ba(e)}
        ${Ja(e)}
      </div>
    </section>
  `}function i(e){return String(e??"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;")}function f(e,a){return`<span class="pill ${a}">${i(e)}</span>`}function Ka(e){return`
    <article class="signal-card tone-${e.tone}">
      <p class="panel-kicker">${i(e.label)}</p>
      <strong>${i(e.value)}</strong>
      <p>${i(e.detail)}</p>
    </article>
  `}function Wa(e){return`
    <article class="metric-card tone-${e.tone}">
      <p class="metric-label">${i(e.label)}</p>
      <p class="metric-value">${i(e.value)}</p>
      <p class="metric-detail">${i(e.detail)}</p>
    </article>
  `}function Qa(e){return`
    <article class="priority-card tone-${e.tone}">
      <div class="priority-topline">
        ${f(e.badge,e.tone)}
        <strong>${i(e.title)}</strong>
      </div>
      <p>${i(e.detail)}</p>
      <p class="priority-action">${i(e.action)}</p>
    </article>
  `}function ye(e){return`
    <article class="route-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${i(e.title)}</p>
          <p class="card-copy">${i(e.detail)}</p>
        </div>
        ${f(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="route-stat-row mono">
        ${e.meta.map(a=>`<span>${i(a)}</span>`).join("")}
      </div>
    </article>
  `}function Ya(e){return`
    <article class="queue-card tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${i(e.title)}</p>
          <p class="card-copy">${i(e.summary)}</p>
        </div>
        ${f(e.badgeLabel,e.badgeTone)}
      </div>
      <div class="queue-card-stats mono">
        ${e.lines.map(a=>`<span>${i(a)}</span>`).join("")}
      </div>
    </article>
  `}function Za(e){return`
    <section class="queue-lane tone-${e.tone}">
      <div class="queue-lane-header">
        <div>
          <p class="panel-kicker">Etapa ${i(e.step)}</p>
          <h3>${i(e.title)}</h3>
          <p class="queue-lane-copy">${i(e.summary)}</p>
        </div>
        ${f(`${e.count} item(ns)`,e.tone)}
      </div>
      <div class="queue-list">
        ${e.cards.length?e.cards.map(Ya).join(""):`<p class="empty-copy">${i(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function et(e){return`
    <article class="readiness-item tone-${e.tone}">
      <p class="panel-kicker">${i(e.label)}</p>
      <strong>${i(e.title)}</strong>
      <p>${i(e.detail)}</p>
    </article>
  `}function at(e){return`
    <article class="event-item tone-${e.tone}">
      <div class="event-topline">
        <div>
          <p class="card-title">${i(e.title)}</p>
          <p class="card-copy">${i(e.subject)}</p>
        </div>
        ${f(e.badgeLabel,e.badgeTone)}
      </div>
      <p>${i(e.detail)}</p>
      <p class="event-meta mono">${i(e.meta)}</p>
    </article>
  `}function tt(e){return`
    <article class="micro-card tone-${e.tone}">
      <p class="panel-kicker">${i(e.label)}</p>
      <strong>${i(e.value)}</strong>
      <p>${i(e.detail)}</p>
    </article>
  `}function nt(e){return`
    <article class="route-card large tone-${e.tone}">
      <div class="queue-card-header">
        <div>
          <p class="card-title">${i(e.title)}</p>
          <p class="card-copy">${i(e.summary)}</p>
        </div>
        ${f(e.badgeLabel,e.badgeTone)}
      </div>
      <p class="mono">${i(e.detail)}</p>
      <div class="tag-row">
        ${e.tags.map(a=>f(a,"muted")).join("")}
      </div>
    </article>
  `}function ot(e){return`
    <section class="panel cockpit-overview">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Cockpit operacional</p>
          <h2>${i(e.headline)}</h2>
          <p class="section-copy">${i(e.executiveSummary)}</p>
        </div>
        ${f(e.modeLabel,e.modeTone)}
      </div>
      <div class="hero-signals">
        ${e.signals.map(Ka).join("")}
      </div>
    </section>

    <section class="metrics-grid">
      ${e.metrics.map(Wa).join("")}
    </section>

    <section class="panel priority-panel">
      <div class="panel-header">
        <div>
          <p class="panel-kicker">Direcionamento imediato</p>
          <h2>O que merece atencao primeiro</h2>
          <p class="section-copy">${i(e.nextActionDetail)}</p>
        </div>
        ${f(e.nextAction,e.nextActionTone)}
      </div>
      <div class="priority-layout">
        <article class="priority-lead tone-${e.leadAlert.tone}">
          <div class="priority-topline">
            ${f("foco da rodada",e.leadAlert.tone)}
            <strong>${i(e.leadAlert.title)}</strong>
          </div>
          <p>${i(e.leadAlert.detail)}</p>
          <p class="priority-action">${i(e.leadAlert.action)}</p>
        </article>
        <div class="priority-support">
          ${e.supportingAlerts.length?e.supportingAlerts.map(Qa).join(""):`
                <article class="priority-card tone-ok">
                  <div class="priority-topline">
                    ${f("estavel","ok")}
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
                <p class="panel-kicker">${i(a.label)}</p>
                <strong>${i(a.value)}</strong>
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
              <p class="section-copy">${i(e.nextActionDetail)}</p>
            </div>
            ${f(e.panelUpdatedAt,"muted")}
          </div>
          <div class="routes-board">
            <article class="route-group tone-${e.activeRoutes.length>0?"info":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas em campo</p>
                  <strong class="route-group-count">${i(String(e.activeRoutes.length))}</strong>
                </div>
                ${f(e.activeRoutes.length>0?`${e.activeRoutes.length} rota(s)`:"sem rota",e.activeRoutes.length>0?"info":"ok")}
              </div>
              <p class="section-copy">${i(e.activeRoutes.length>0?"Entregas rodando e pedindo acompanhamento de execucao.":"Nenhuma rota ativa na leitura atual.")}</p>
              <div class="route-list">
                ${e.activeRoutes.length?e.activeRoutes.map(ye).join(""):'<p class="empty-copy">Sem rota ativa.</p>'}
              </div>
            </article>
            <article class="route-group tone-${e.plannedRoutes.length>0?"warn":"ok"}">
              <div class="route-group-header">
                <div>
                  <p class="panel-kicker">Rotas prontas</p>
                  <strong class="route-group-count">${i(String(e.plannedRoutes.length))}</strong>
                </div>
                ${f(e.plannedRoutes.length>0?`${e.plannedRoutes.length} rota(s)`:"sem rota",e.plannedRoutes.length>0?"warn":"ok")}
              </div>
              <p class="section-copy">${i(e.plannedRoutes.length>0?"Carga ja comprometida aguardando decisao para ganhar rua.":"Nenhuma rota pronta aguardando liberacao.")}</p>
              <div class="route-list">
                ${e.plannedRoutes.length?e.plannedRoutes.map(ye).join(""):'<p class="empty-copy">Sem rota planejada.</p>'}
              </div>
            </article>
          </div>
          <div class="queue-grid">
            ${e.queueLanes.map(Za).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <p class="panel-kicker">Mapa operacional</p>
              <h2>Camadas e rotas</h2>
              <p class="section-copy">${i(e.pulses.find(a=>a.label==="Rotas")?.value||"Sem resumo de rotas.")}</p>
            </div>
            ${e.mapDeposit?f(e.mapDeposit,"muted"):""}
          </div>
          <div class="micro-grid">
            ${e.mapSummaryCards.map(tt).join("")}
          </div>
          <div class="route-list">
            ${e.mapRoutes.length?e.mapRoutes.map(nt).join(""):'<p class="empty-copy">Sem rotas mapeadas.</p>'}
          </div>
        </section>
      </div>

      <aside class="content-side">
        <section class="panel readiness-panel">
          <div class="readiness-summary">
            <div>
              <p class="panel-kicker">Confianca da leitura</p>
              <h2>${i(e.confidenceLabel)}</h2>
              <p class="section-copy">${i(e.confidenceDetail)}</p>
            </div>
            <div class="readiness-meta">
              ${f(e.readinessStatus.title,e.readinessStatus.tone)}
            </div>
          </div>
          <div class="readiness-grid">
            ${e.readinessItems.map(et).join("")}
          </div>
          ${e.notices.map(a=>`
                <div class="notice notice-${a.tone}">
                  <strong>${i(a.label)}:</strong> ${i(a.body)}
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
            ${f(e.eventBadgeLabel,e.eventBadgeTone)}
          </div>
          <div class="event-list">
            ${e.events.length?e.events.map(at).join(""):'<p class="empty-copy">Nenhum evento retornado.</p>'}
          </div>
        </section>
      </aside>
    </main>
  `}function Fe(e){if(!e||!e.painel)return{alerts:[{tone:"warn",title:"Sem leitura operacional suficiente",detail:"A interface ainda nao conseguiu carregar o painel principal.",action:"Validar API base e atualizar a sincronizacao."}],headline:"Aguardando dados operacionais",queueSummary:"Fila indisponivel",routeSummary:"Rotas indisponiveis",eventSummary:"Eventos indisponiveis",executiveSummary:"A operacao ainda nao entregou leitura suficiente para orientar uma decisao com seguranca.",modeLabel:"Aguardando leitura",modeTone:"warn",nextAction:"Validar conexao",nextActionDetail:"Conferir API base e executar nova sincronizacao antes de operar.",nextActionTone:"warn",confidenceLabel:"Baixa confianca",confidenceDetail:"Sem painel principal, qualquer decisao pode estar desatualizada.",confidenceTone:"danger"};const a=e.painel,t=e.mapa,n=e.eventos?.eventos||[],o=[];(e.health?.status!=="ok"||e.health?.database!=="ok")&&o.push({tone:"danger",title:"Infra com degradacao",detail:"API ou banco nao responderam como esperado nesta leitura.",action:"Conferir health e estabilizar a base antes de operar manualmente."}),e.partialErrors.length>0&&o.push({tone:"warn",title:"Visao parcial da operacao",detail:e.partialErrors.join(" | "),action:"Atualizar novamente antes de tomar decisao de despacho."});const r=a.filas.pendentesElegiveis.filter(j=>j.janelaTipo==="HARD").length;r>0&&o.push({tone:"danger",title:`${r} pedido(s) HARD aguardando despacho`,detail:"Esses pedidos merecem prioridade maxima porque a janela e mais restrita.",action:"Separar esses pedidos primeiro na avaliacao de fila."});const s=a.rotas.planejadas.length;s>0&&o.push({tone:"warn",title:`${s} rota(s) pronta(s) para iniciar`,detail:"Existe carga ja comprometida aguardando acao operacional.",action:"Confirmar se a capacidade da frota permite iniciar a proxima rota."});const A=Ue(n);A>0&&o.push({tone:"info",title:`${A} ocorrencia(s) recente(s) de falha/cancelamento`,detail:"O feed operacional registrou eventos de excecao nas ultimas leituras.",action:"Olhar o bloco de ocorrencias para entender o contexto antes da proxima acao."});const b=lt(t);b.consistent||o.push({tone:"warn",title:"Camadas da frota fora do esperado",detail:b.message,action:"Revisar leitura de rotas antes de assumir que existe uma unica primaria e uma unica secundaria."}),o.length===0&&o.push({tone:"ok",title:"Operacao em regime estavel",detail:"Nao surgiram sinais imediatos de excecao na leitura atual.",action:"Seguir monitorando fila, eventos e rotas por cadencia normal."});const E=rt(a),$=it(a),S=st(a,t,b.message),d=dt(n),I=o[0];return{alerts:o.slice(0,4),headline:E,queueSummary:$,routeSummary:S,eventSummary:d,executiveSummary:`${E}. ${$} ${S}`,modeLabel:I?.title||"Operacao estavel",modeTone:I?.tone||"ok",nextAction:I?.action||"Seguir monitorando",nextActionDetail:I?.detail||"Sem excecoes imediatas na leitura atual.",nextActionTone:I?.tone||"ok",confidenceLabel:e.partialErrors.length>0?"Confianca moderada":e.health?.status==="ok"?"Confianca alta":"Confianca reduzida",confidenceDetail:e.partialErrors.length>0?"Ha leituras parciais nesta sincronizacao; valide antes de decidir.":e.health?.status==="ok"?"Painel, eventos e mapa responderam como esperado.":"Infra ou banco sinalizaram degradacao.",confidenceTone:e.partialErrors.length>0?"warn":e.health?.status==="ok"?"ok":"danger"}}function Ue(e){return e.filter(a=>{const t=String(a.eventType||"").toUpperCase();return t.includes("FALHOU")||t.includes("CANCELADO")}).length}function rt(e){return e.filas.pendentesElegiveis.length>0?"Fila exigindo triagem ativa":e.rotas.emAndamento.length>0?"Entrega em curso com operacao ja movimentada":e.rotas.planejadas.length>0?"Operacao pronta para iniciar nova rota":"Base limpa para acompanhamento"}function it(e){return`${e.pedidosPorStatus.pendente} pendente(s), ${e.pedidosPorStatus.confirmado} confirmado(s), ${e.pedidosPorStatus.emRota} em rota.`}function st(e,a,t){const n=a?.rotas.filter(r=>r.camada==="PRIMARIA").length??0,o=a?.rotas.filter(r=>r.camada==="SECUNDARIA").length??0;return`${e.rotas.emAndamento.length} em andamento, ${e.rotas.planejadas.length} planejada(s), ${n} primaria(s), ${o} secundaria(s). ${t}`}function dt(e){if(e.length===0)return"Sem ocorrencias recentes.";const a=Ue(e);return a>0?`${a} excecao(oes) recente(s) em ${e.length} evento(s) lido(s).`:`${e.length} evento(s) recente(s) sem excecao imediata.`}function lt(e){if(!e||e.rotas.length===0)return{consistent:!0,message:"Sem rotas mapeadas."};const a=e.rotas.filter(o=>o.camada==="PRIMARIA").length,t=e.rotas.filter(o=>o.camada==="SECUNDARIA").length;return a<=1&&t<=1?{consistent:!0,message:"Camadas dentro da expectativa atual."}:{consistent:!1,message:`Leitura retornou ${a} primaria(s) e ${t} secundaria(s).`}}function P(e){if(!e)return"-";const a=new Date(e);return Number.isNaN(a.getTime())?e:a.toLocaleString("pt-BR")}function Me(e){return typeof e!="number"||Number.isNaN(e)?"-":`${e.toFixed(1)}%`}function te(e){if(!e)return"-";const a=new Date(e);if(Number.isNaN(a.getTime()))return e;const t=Date.now()-a.getTime(),n=Math.round(Math.abs(t)/6e4);if(n<1)return"agora";if(n<60)return t>=0?`${n} min atras`:`em ${n} min`;const o=Math.round(n/60);if(o<24)return t>=0?`${o} h atras`:`em ${o} h`;const r=Math.round(o/24);return t>=0?`${r} dia(s) atras`:`em ${r} dia(s)`}function D(e){return e?e.toLowerCase().split(/[_-]+/).filter(Boolean).map(a=>a.charAt(0).toUpperCase()+a.slice(1)).join(" "):"-"}function ct(e){return e==="health"?"Health":e==="painel"?"Painel":e==="eventos"?"Eventos":"Mapa"}function ut(e){return e==="ok"?"ok":e==="error"?"danger":"muted"}function pt(e){return e==="danger"?"agir agora":e==="warn"?"atencao":e==="info"?"acompanhar":"estavel"}function ze(e){const a=String(e.eventType||"").toUpperCase(),t=String(e.status||"").toUpperCase();return a.includes("FALHOU")||a.includes("CANCELADO")||t.includes("ERRO")||t.includes("FALHA")?"danger":a.includes("ENTREGUE")?"ok":a.includes("ROTA")?"info":"warn"}function mt(e){return e==="PRIMARIA"?"info":"warn"}function Be(e){return(e.snapshot?.eventos?.eventos||[]).filter(t=>ze(t)==="danger").length}function gt(e){const a=e.snapshot?.painel,t=a?.filas.pendentesElegiveis.filter(E=>E.janelaTipo==="HARD").length??0,n=a?.rotas.emAndamento.length??0,o=a?.rotas.planejadas.length??0,r=Be(e),s=a?.indicadoresEntrega.taxaSucessoPercentual,A=a?.indicadoresEntrega.totalFinalizadas,b=a?.indicadoresEntrega.entregasConcluidas;return[{label:"Fila para triar",value:a?String(a.filas.pendentesElegiveis.length):"-",detail:a?`${a.pedidosPorStatus.pendente} pedido(s) pendente(s) na visao geral`:"aguardando leitura do painel",tone:a?t>0?"danger":a.filas.pendentesElegiveis.length>0?"warn":"ok":"muted"},{label:"Pedidos HARD",value:a?String(t):"-",detail:t>0?"janela critica exigindo prioridade maxima":"sem pedido critico escondido na fila",tone:a?t>0?"danger":"ok":"muted"},{label:"Rotas prontas",value:a?String(o):"-",detail:o>0?"aguardando decisao de liberar saida":"sem rota pronta esperando liberacao",tone:a?o>0?"warn":"ok":"muted"},{label:"Rotas em campo",value:a?String(n):"-",detail:n>0?"execucao ativa pedindo acompanhamento":"sem entrega em curso nesta rodada",tone:a?n>0?"info":"ok":"muted"},{label:"Excecoes recentes",value:e.snapshot?String(r):"-",detail:r>0?"falhas ou cancelamentos visiveis no feed":"feed recente sem falha aparente",tone:e.snapshot?r>=3?"danger":r>0?"warn":"ok":"muted"},{label:"Taxa de sucesso",value:a?Me(s):"-",detail:a&&typeof A=="number"&&typeof b=="number"?`${b} concluida(s) em ${A} finalizada(s)`:"indicador ainda indisponivel",tone:!a||typeof s!="number"?"muted":s>=95?"ok":s>=85?"warn":"danger"}]}function ft(e){const a=e.snapshot?.readiness;return["health","painel","eventos","mapa"].map(n=>{const o=a?.[n]||"unknown";return{label:ct(n),title:o==="ok"?"Leitura pronta":o==="error"?"Falhou nesta rodada":"Aguardando resposta",detail:o==="ok"?"Fonte pronta para sustentar o cockpit.":o==="error"?"Precisa de nova tentativa ou validacao manual.":"Ainda sem retorno desta fonte.",tone:ut(o)}})}function ht(e){const a=[];return e.snapshot?.partialErrors.length&&a.push({label:"Leitura parcial",body:e.snapshot.partialErrors.join(" | "),tone:"warn"}),e.sync.lastError&&a.push({label:"Ultimo erro",body:e.sync.lastError,tone:"danger"}),a}function vt(e){return(e.snapshot?.painel?.rotas.emAndamento||[]).map(t=>({title:`R${t.rotaId}`,detail:`Entregador ${t.entregadorId}`,meta:[`${t.pendentes} pendente(s)`,`${t.emExecucao} em execucao`],badgeLabel:"em campo",badgeTone:"info",tone:"info"}))}function $t(e){return(e.snapshot?.painel?.rotas.planejadas||[]).map(t=>({title:`R${t.rotaId}`,detail:`Entregador ${t.entregadorId}`,meta:[`${t.pendentes} parada(s) pronta(s)`],badgeLabel:"aguardando saida",badgeTone:"warn",tone:"warn"}))}function bt(e){const a=e.janelaTipo==="HARD";return{title:`Pedido #${e.pedidoId}`,summary:a?"Janela critica para despacho.":"Janela flexivel em triagem.",badgeLabel:a?"prioridade maxima":"fila ativa",badgeTone:a?"danger":"warn",tone:a?"danger":"warn",lines:[`${e.quantidadeGaloes} galao(oes)`,`Criado ${te(e.criadoEm)}`,P(e.criadoEm)]}}function At(e){return{title:`Pedido #${e.pedidoId}`,summary:"Carga confirmada na secundaria aguardando liberacao de rota.",badgeLabel:`R${e.rotaId}`,badgeTone:"info",tone:"info",lines:[`Entregador ${e.entregadorId}`,`Ordem ${e.ordemNaRota}`,`${e.quantidadeGaloes} galao(oes)`]}}function Et(e){return{title:`Pedido #${e.pedidoId}`,summary:`Entrega em andamento na rota ${e.rotaId}.`,badgeLabel:D(e.statusEntrega),badgeTone:"ok",tone:"ok",lines:[`Entrega ${e.entregaId}`,`Entregador ${e.entregadorId}`,`${e.quantidadeGaloes} galao(oes)`]}}function It(e){const a=e.snapshot?.painel?.filas,t=a?.pendentesElegiveis.filter(n=>n.janelaTipo==="HARD").length??0;return[{step:"1",title:"Triar pedidos novos",summary:t>0?`${t} pedido(s) HARD precisam abrir a decisao desta fila.`:(a?.pendentesElegiveis.length??0)>0?"Fila ativa aguardando alocacao e priorizacao.":"Entrada limpa nesta rodada.",tone:t>0?"danger":(a?.pendentesElegiveis.length??0)>0?"warn":"ok",count:a?.pendentesElegiveis.length??0,cards:(a?.pendentesElegiveis||[]).map(bt),emptyMessage:"Nenhum pedido pendente elegivel."},{step:"2",title:"Liberar carga preparada",summary:(a?.confirmadosSecundaria.length??0)>0?"Pedidos ja encaixados em rota secundaria aguardando o aval operacional.":"Sem carga pronta aguardando liberacao.",tone:(a?.confirmadosSecundaria.length??0)>0?"warn":"ok",count:a?.confirmadosSecundaria.length??0,cards:(a?.confirmadosSecundaria||[]).map(At),emptyMessage:"Nenhum pedido aguardando rota secundaria."},{step:"3",title:"Acompanhar entrega em curso",summary:(a?.emRotaPrimaria.length??0)>0?"Entregas em rua que pedem monitoramento continuo.":"Sem pedido em execucao agora.",tone:(a?.emRotaPrimaria.length??0)>0?"info":"ok",count:a?.emRotaPrimaria.length??0,cards:(a?.emRotaPrimaria||[]).map(Et),emptyMessage:"Nenhum pedido em execucao agora."}]}function yt(e){return(e.snapshot?.eventos?.eventos||[]).slice(0,8).map(t=>{const n=ze(t);return{title:D(t.eventType),badgeLabel:D(t.status),badgeTone:n,subject:`${D(t.aggregateType)} ${t.aggregateId??"-"}`,detail:n==="danger"?"Excecao recente com potencial de alterar a prioridade operacional.":"Movimento recente da operacao registrado no feed.",meta:t.processedEm?`${te(t.createdEm)} · criado ${P(t.createdEm)} · processado ${P(t.processedEm)}`:`${te(t.createdEm)} · criado ${P(t.createdEm)}`,tone:n}})}function St(e){const a=e.snapshot?.mapa?.rotas||[],t=a.filter(r=>r.camada==="PRIMARIA").length,n=a.filter(r=>r.camada==="SECUNDARIA").length,o=a.reduce((r,s)=>r+s.paradas.length,0);return[{label:"Primarias",value:String(t),detail:"Rotas que sustentam a execucao principal.",tone:"info"},{label:"Secundarias",value:String(n),detail:"Rotas de apoio, preparacao ou contingencia.",tone:"warn"},{label:"Paradas mapeadas",value:String(o),detail:"Total de entregas representadas nesta leitura.",tone:"ok"}]}function Ct(e){return(e.snapshot?.mapa?.rotas||[]).map(t=>{const n=t.paradas.filter(s=>String(s.statusEntrega).toUpperCase().includes("ENTREGUE")).length,o=Math.max(t.paradas.length-n,0),r=mt(t.camada);return{title:`R${t.rotaId} · Entregador ${t.entregadorId}`,badgeLabel:t.camada==="PRIMARIA"?"camada primaria":"camada secundaria",badgeTone:r,summary:D(t.statusRota),detail:`${t.paradas.length} parada(s) · ${t.trajeto.length} ponto(s) no trajeto · ${n} concluida(s) · ${o} aberta(s)`,tags:t.paradas.slice(0,4).map(s=>`P${s.ordemNaRota} pedido ${s.pedidoId}`),tone:r}})}function Rt(e){const a=Fe(e.snapshot),t=a.alerts.map(o=>({badge:pt(o.tone),tone:o.tone,title:o.title,detail:o.detail,action:o.action})),n=Be(e);return{headline:a.headline,executiveSummary:a.executiveSummary,modeLabel:a.modeLabel,modeTone:a.modeTone,nextAction:a.nextAction,nextActionDetail:a.nextActionDetail,nextActionTone:a.nextActionTone,confidenceLabel:a.confidenceLabel,confidenceDetail:a.confidenceDetail,confidenceTone:a.confidenceTone,signals:[{label:"Modo atual",value:a.modeLabel,detail:a.executiveSummary,tone:a.modeTone},{label:"Proxima decisao",value:a.nextAction,detail:a.nextActionDetail,tone:a.nextActionTone},{label:"Confianca",value:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone}],metrics:gt(e),leadAlert:t[0],supportingAlerts:t.slice(1),pulses:[{label:"Fila",value:a.queueSummary},{label:"Rotas",value:a.routeSummary},{label:"Ocorrencias",value:a.eventSummary}],readinessStatus:{label:a.confidenceLabel,title:a.confidenceLabel,detail:a.confidenceDetail,tone:a.confidenceTone},readinessItems:ft(e),notices:ht(e),panelUpdatedAt:P(e.snapshot?.painel?.atualizadoEm),activeRoutes:vt(e),plannedRoutes:$t(e),queueLanes:It(e),eventBadgeLabel:n>0?`${n} excecao(oes)`:"sem excecao",eventBadgeTone:n>=3?"danger":n>0?"warn":"ok",events:yt(e),mapDeposit:e.snapshot?.mapa?`Deposito ${e.snapshot.mapa.deposito.lat.toFixed(4)}, ${e.snapshot.mapa.deposito.lon.toFixed(4)}`:null,mapSummaryCards:St(e),mapRoutes:Ct(e)}}function xt(e){return`
    <article class="metric-card">
      <p class="metric-label">${i(e.label)}</p>
      <p class="metric-value">${i(e.value)}</p>
      <p class="metric-detail">${i(e.detail)}</p>
    </article>
  `}function Pt(e){return`
    <article class="priority-card tone-${e.tone}">
      <div class="priority-topline">
        ${f(e.badge,e.tone)}
        <strong>${i(e.title)}</strong>
      </div>
      <p>${i(e.detail)}</p>
      <p class="priority-action">${i(e.action)}</p>
    </article>
  `}function Nt(e){return`
    <section class="queue-lane">
      <div class="queue-lane-header">
        <div>
          <h3>${i(e.title)}</h3>
          <p class="queue-lane-copy">${i(e.summary)}</p>
        </div>
        ${f(e.tone,e.tone)}
      </div>
      <div class="queue-list">
        ${e.cards.length>0?e.cards.map(a=>`
                    <article class="queue-card">
                      <div class="queue-card-header">
                        <strong>${i(a.title)}</strong>
                        ${f(a.badgeLabel,a.badgeTone)}
                      </div>
                      ${a.lines.map(t=>`<p class="mono">${i(t)}</p>`).join("")}
                      <p class="card-copy">${i(a.action)}</p>
                    </article>
                  `).join(""):`<p class="empty-copy">${i(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function jt(e){return`
    <article class="route-group">
      <div class="route-group-header">
        <div>
          <h3>${i(e.title)}</h3>
          <p class="section-copy">${i(e.summary)}</p>
        </div>
        ${f(String(e.routes.length),e.tone)}
      </div>
      <div class="route-list">
        ${e.routes.length>0?e.routes.map(a=>`
                    <article class="route-card">
                      <strong>${i(a.title)}</strong>
                      <p class="mono">${i(a.meta)}</p>
                    </article>
                  `).join(""):`<p class="empty-copy">${i(e.emptyMessage)}</p>`}
      </div>
    </article>
  `}function Tt(e){return`
    <section class="event-bucket">
      <div class="panel-header">
        <div>
          <h3>${i(e.title)}</h3>
          <p class="section-copy">${i(e.summary)}</p>
        </div>
        ${f(String(e.cards.length),e.tone)}
      </div>
      <div class="event-list">
        ${e.cards.length>0?e.cards.map(a=>`
                    <article class="event-item">
                      <div class="event-topline">
                        <strong>${i(a.title)}</strong>
                        ${f(a.badgeLabel,a.badgeTone)}
                      </div>
                      <p class="mono">${i(a.subject)}</p>
                      <p class="event-meta">${i(a.meta)}</p>
                      <p class="card-copy">${i(a.detail)}</p>
                    </article>
                  `).join(""):`<p class="empty-copy">${i(e.emptyMessage)}</p>`}
      </div>
    </section>
  `}function Dt(e){return`
    <article class="route-card large">
      <div class="queue-card-header">
        <strong>${i(e.title)}</strong>
        ${f(e.badgeLabel,e.badgeTone)}
      </div>
      <p class="mono">${i(e.summary)}</p>
      <div class="tag-row">
        ${e.tags.map(a=>f(a,"muted")).join("")}
      </div>
    </article>
  `}function kt(e){return`
    <aside class="dispatch-action-card tone-${e.tone}">
      <div class="panel-header">
        <div>
          <h2>${i(e.title)}</h2>
          <p class="section-copy">${i(e.detail)}</p>
        </div>
        ${f(e.badgeLabel,e.tone)}
      </div>
      <p class="card-copy">${i(e.supportingText)}</p>
      ${e.blocker?`<div class="notice notice-warn"><strong>Bloqueio:</strong> ${i(e.blocker)}</div>`:""}
      <button
        class="button primary"
        type="button"
        data-action="start-prepared-route"
        data-entregador-id="${i(e.entregadorId??"")}"
        ${e.enabled?"":"disabled"}
      >
        ${i(e.buttonLabel)}
      </button>
    </aside>
  `}function Lt(e){return`
    <section class="dispatch-stack">
      <section class="panel dispatch-hero-panel">
        <div class="dispatch-hero-grid">
          <div>
            <p class="panel-kicker">Despacho</p>
            <h2>${i(e.headline)}</h2>
            <p class="section-copy">${i(e.summary)}</p>
            <div class="metrics-grid">
              ${e.metrics.map(xt).join("")}
            </div>
          </div>
          ${kt(e.action)}
        </div>
      </section>

      <section class="panel priority-panel">
        <div class="panel-header">
          <div>
            <h2>Prioridades de despacho</h2>
            <p class="section-copy">Leitura guiada para decisao em fila, risco, frota e ocorrencias.</p>
          </div>
          ${f("modulo ativo","info")}
        </div>
        <div class="priority-grid">
          ${e.priorities.map(Pt).join("")}
        </div>
        <div class="pulse-grid">
          ${e.pulses.map(a=>`
                <article class="pulse-card">
                  <p class="label">${i(a.label)}</p>
                  <p>${i(a.value)}</p>
                </article>
              `).join("")}
        </div>
      </section>

      ${e.notices.length>0?`
            <section class="dispatch-notice-stack">
              ${e.notices.map(a=>`
                    <div class="notice notice-${a.tone}">
                      <strong>${i(a.label)}:</strong> ${i(a.body)}
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
          ${f(String(e.queueLanes.reduce((a,t)=>a+t.cards.length,0)),"info")}
        </div>
        <div class="queue-grid">
          ${e.queueLanes.map(Nt).join("")}
        </div>
      </section>

      <main class="dispatch-grid">
        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>Camadas de frota</h2>
              <p class="section-copy">Separacao clara entre primaria, secundaria e qualquer incoerencia do read model.</p>
            </div>
            ${f(String(e.layerWarnings.length),e.layerWarnings.length>0?"warn":"ok")}
          </div>
          ${e.layerWarnings.length>0?`<div class="notice notice-warn"><strong>Anomalias de camada:</strong> ${i(e.layerWarnings.join(" | "))}</div>`:'<div class="notice notice-ok"><strong>Modelo consistente:</strong> primaria e secundaria legiveis nesta leitura.</div>'}
          <div class="dispatch-layer-grid">
            ${e.layerCards.map(jt).join("")}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <div>
              <h2>Feed de ocorrencias</h2>
              <p class="section-copy">O feed separa o que pede acao imediata do que e apenas movimento normal da operacao.</p>
            </div>
            ${f(String(e.eventBuckets.reduce((a,t)=>a+t.cards.length,0)),"info")}
          </div>
          <div class="dispatch-event-grid">
            ${e.eventBuckets.map(Tt).join("")}
          </div>
        </section>

        <section class="panel dispatch-routes-panel">
          <div class="panel-header">
            <div>
              <h2>Rotas e mapa operacional</h2>
              <p class="section-copy">${i(e.routeSummary)}</p>
            </div>
            ${e.mapDeposit?f(e.mapDeposit,"muted"):f("sem mapa","warn")}
          </div>
          <div class="route-list">
            ${e.routeCards.length>0?e.routeCards.map(Dt).join(""):'<p class="empty-copy">Nenhuma rota retornada pelo mapa operacional.</p>'}
          </div>
        </section>
      </main>
    </section>
  `}function Se(e){return e.tone}function Ot(e){return e==="danger"?"agir agora":e==="warn"?"atencao":e==="info"?"acompanhar":"estavel"}function B(e){const a=String(e.eventType||"").toUpperCase();return a.includes("FALHOU")||a.includes("CANCELADO")?"danger":a.includes("ENTREGUE")?"ok":a.includes("ROTA")?"info":"warn"}function qt(e,a){const t=String(e||"").toUpperCase();if(t.includes("PRIMARIA"))return"PRIMARIA";if(t.includes("SECUNDARIA"))return"SECUNDARIA";const n=String(a||"").toUpperCase();return n==="EM_ANDAMENTO"?"PRIMARIA":n==="PLANEJADA"?"SECUNDARIA":"DESCONHECIDA"}function wt(e){switch(String(e||"").toUpperCase()){case"EM_EXECUCAO":return"emExecucao";case"ENTREGUE":case"CONCLUIDA":case"CANCELADA":return"concluidas";default:return"pendentes"}}function Ft(e){const a=new Map;for(const t of e?.mapa?.rotas??[]){const n=Ut(t);a.set(t.rotaId,n)}for(const t of e?.painel?.rotas.emAndamento??[]){const n=a.get(t.rotaId);a.set(t.rotaId,{routeId:t.rotaId,entregadorId:t.entregadorId,camada:"PRIMARIA",statusRota:n?.statusRota??"EM_ANDAMENTO",paradas:n?.paradas??t.pendentes+t.emExecucao,pontosTrajeto:n?.pontosTrajeto??0,pendentes:t.pendentes,emExecucao:t.emExecucao,concluidas:n?.concluidas??0,quantidadeGaloes:n?.quantidadeGaloes??0,tags:n?.tags??[]})}for(const t of e?.painel?.rotas.planejadas??[]){const n=a.get(t.rotaId);a.set(t.rotaId,{routeId:t.rotaId,entregadorId:t.entregadorId,camada:"SECUNDARIA",statusRota:n?.statusRota??"PLANEJADA",paradas:n?.paradas??t.pendentes,pontosTrajeto:n?.pontosTrajeto??0,pendentes:t.pendentes,emExecucao:n?.emExecucao??0,concluidas:n?.concluidas??0,quantidadeGaloes:n?.quantidadeGaloes??0,tags:n?.tags??[]})}return[...a.values()].sort((t,n)=>Ce(t.camada)-Ce(n.camada)||t.routeId-n.routeId)}function Ut(e){const a=e.paradas.reduce((t,n)=>(t[wt(n.statusEntrega)]+=1,t.quantidadeGaloes+=n.quantidadeGaloes,t),{pendentes:0,emExecucao:0,concluidas:0,quantidadeGaloes:0});return{routeId:e.rotaId,entregadorId:e.entregadorId,camada:qt(e.camada,e.statusRota),statusRota:e.statusRota,paradas:e.paradas.length,pontosTrajeto:e.trajeto.length,pendentes:a.pendentes,emExecucao:a.emExecucao,concluidas:a.concluidas,quantidadeGaloes:a.quantidadeGaloes,tags:e.paradas.slice().sort((t,n)=>t.ordemNaRota-n.ordemNaRota).slice(0,5).map(t=>`P${t.ordemNaRota} · pedido ${t.pedidoId}`)}}function Ce(e){return e==="PRIMARIA"?0:e==="SECUNDARIA"?1:2}function Mt(e){const a=e.filter(r=>r.camada==="PRIMARIA").length,t=e.filter(r=>r.camada==="SECUNDARIA").length,n=e.filter(r=>r.camada==="DESCONHECIDA").length,o=[];return a>1&&o.push(`Foram detectadas ${a} rotas na camada PRIMARIA.`),t>1&&o.push(`Foram detectadas ${t} rotas na camada SECUNDARIA.`),n>0&&o.push(`${n} rota(s) vieram sem camada reconhecivel.`),o}function zt(e){const a=String(e.janelaTipo||"ASAP").toUpperCase();return{title:`Pedido #${e.pedidoId}`,badgeLabel:a,badgeTone:a==="HARD"?"danger":a==="ASAP"?"warn":"info",lines:[`${e.quantidadeGaloes} galao(oes)`,`Criado em ${P(e.criadoEm)}`],action:a==="HARD"?"Priorizar este encaixe antes da proxima rodada.":"Avaliar com a proxima disponibilidade da frota."}}function Bt(e){return{title:`Pedido #${e.pedidoId}`,badgeLabel:`R${e.rotaId}`,badgeTone:"info",lines:[`Entregador ${e.entregadorId} · ordem ${e.ordemNaRota}`,`${e.quantidadeGaloes} galao(oes) ja comprometidos`],action:"Checar contexto da secundaria antes de girar a rota."}}function Ht(e){return{title:`Pedido #${e.pedidoId}`,badgeLabel:e.statusEntrega,badgeTone:e.statusEntrega==="EM_EXECUCAO"?"ok":"info",lines:[`Entrega ${e.entregaId} · rota ${e.rotaId}`,`Entregador ${e.entregadorId} · ${e.quantidadeGaloes} galao(oes)`],action:"Monitorar excecoes e liberar espaco para a proxima onda."}}function _t(e){const a=Object.entries(e.payload??{}).filter(([,t])=>{const n=typeof t;return n==="string"||n==="number"||n==="boolean"}).slice(0,2).map(([t,n])=>`${t}: ${String(n)}`);return a.length>0?a.join(" · "):"Sem detalhe resumivel."}function Re(e){const a=B(e);return{title:e.eventType,badgeLabel:a==="danger"?"agir":e.status,badgeTone:a,subject:`${e.aggregateType} ${e.aggregateId??"-"}`,meta:e.processedEm?`criado ${P(e.createdEm)} · processado ${P(e.processedEm)}`:`criado ${P(e.createdEm)}`,detail:_t(e)}}function Gt(e){const a=e.snapshot,t=a?.painel,n=Fe(a),o=Ft(a),r=Mt(o),s=(a?.eventos?.eventos??[]).filter(d=>B(d)==="danger").length,A=t?.filas.pendentesElegiveis.filter(d=>String(d.janelaTipo).toUpperCase()==="HARD").length??0,b=o.filter(d=>d.camada==="SECUNDARIA"),E=b[0]??null,$=[];t||$.push("Painel operacional indisponivel."),a?.mapa||$.push("Mapa operacional indisponivel."),r.length>0&&$.push(r[0]),b.length===0&&$.push("Nenhuma rota secundaria pronta para iniciar."),b.length>1&&$.push("Mais de uma secundaria apareceu na mesma leitura.");const S=$.length===0&&E!==null;return{headline:t?A>0?"Fila pressionando a triagem":b.length>0?"Saida pronta para decisao":s>0?"Excecoes pedindo leitura cuidadosa":"Despacho sob controle no recorte atual":"Aguardando contexto seguro de despacho",summary:t?`${t.filas.pendentesElegiveis.length} entrada(s) na triagem · ${t.rotas.planejadas.length} rota(s) planejada(s) · ${s} excecao(oes) recente(s).`:"Sem painel principal nao ha contexto suficiente para girar a frota com seguranca.",metrics:[{label:"Pendentes criticos",value:String(A),detail:"Pedidos HARD exigindo resposta curta.",tone:A>0?"danger":"ok"},{label:"Fila de triagem",value:String(t?.filas.pendentesElegiveis.length??0),detail:"Entradas novas aguardando encaixe.",tone:(t?.filas.pendentesElegiveis.length??0)>0?"warn":"ok"},{label:"Secundaria pronta",value:String(t?.rotas.planejadas.length??0),detail:"Carga preparada para virar saida.",tone:(t?.rotas.planejadas.length??0)>0?"info":"muted"},{label:"Primaria em curso",value:String(t?.rotas.emAndamento.length??0),detail:"Rotas que ja sustentam a operacao.",tone:(t?.rotas.emAndamento.length??0)>0?"ok":"muted"},{label:"Excecoes recentes",value:String(s),detail:"Falhas e cancelamentos no feed.",tone:s>0?"danger":"ok"},{label:"Taxa de sucesso",value:Me(t?.indicadoresEntrega.taxaSucessoPercentual),detail:"Finalizacao acumulada do read model.",tone:(t?.indicadoresEntrega.taxaSucessoPercentual??0)>=90?"ok":"info"}],priorities:n.alerts.map(d=>({badge:Ot(Se(d)),tone:Se(d),title:d.title,detail:d.detail,action:d.action})),pulses:[{label:"Leitura da fila",value:n.queueSummary},{label:"Leitura das rotas",value:n.routeSummary},{label:"Leitura das ocorrencias",value:n.eventSummary}],queueLanes:[{title:"Triagem imediata",tone:A>0?"danger":(t?.filas.pendentesElegiveis.length??0)>0?"warn":"ok",summary:(t?.filas.pendentesElegiveis.length??0)>0?`${t?.filas.pendentesElegiveis.length??0} pedido(s) aguardando avaliacao.`:"Sem nova entrada pressionando a fila.",cards:(t?.filas.pendentesElegiveis??[]).slice().sort((d,I)=>d.criadoEm.localeCompare(I.criadoEm)).map(zt),emptyMessage:"Nenhum pedido pendente elegivel neste momento."},{title:"Preparar saida",tone:(t?.filas.confirmadosSecundaria.length??0)>0?"info":(t?.rotas.planejadas.length??0)>0?"warn":"muted",summary:(t?.filas.confirmadosSecundaria.length??0)>0?`${t?.filas.confirmadosSecundaria.length??0} pedido(s) comprometidos na secundaria.`:(t?.rotas.planejadas.length??0)>0?"Existe rota pronta, mas sem detalhamento dos pedidos nesta leitura.":"Sem carga montada para uma nova saida.",cards:(t?.filas.confirmadosSecundaria??[]).map(Bt),emptyMessage:(t?.rotas.planejadas.length??0)>0?"A secundaria existe, mas o read model nao detalhou os pedidos desta rota.":"Nenhum pedido confirmado aguardando saida."},{title:"Monitorar execucao",tone:(t?.filas.emRotaPrimaria.length??0)>0?"ok":"muted",summary:(t?.filas.emRotaPrimaria.length??0)>0?`${t?.filas.emRotaPrimaria.length??0} entrega(s) em circulacao na primaria.`:"Sem entrega ativa na primaria agora.",cards:(t?.filas.emRotaPrimaria??[]).map(Ht),emptyMessage:"Nenhum pedido em execucao agora."}],layerCards:[{title:"Camada primaria",tone:o.some(d=>d.camada==="PRIMARIA")?"ok":"muted",summary:"Sustenta a execucao que ja saiu para a rua.",routes:o.filter(d=>d.camada==="PRIMARIA").map(d=>({title:`R${d.routeId} · Entregador ${d.entregadorId}`,meta:`${d.pendentes} pendente(s) · ${d.emExecucao} em execucao · ${d.concluidas} concluida(s)`})),emptyMessage:"Nenhuma rota primaria encontrada."},{title:"Camada secundaria",tone:o.some(d=>d.camada==="SECUNDARIA")?"info":"muted",summary:"Reserva operacional pronta para a proxima decisao.",routes:o.filter(d=>d.camada==="SECUNDARIA").map(d=>({title:`R${d.routeId} · Entregador ${d.entregadorId}`,meta:`${d.pendentes} pendente(s) · ${d.paradas} parada(s) previstas`})),emptyMessage:"Nenhuma rota secundaria pronta neste recorte."},{title:"Leitura inconsistente",tone:o.some(d=>d.camada==="DESCONHECIDA")?"warn":"muted",summary:"Qualquer rota sem camada clara pede dupla checagem antes de agir.",routes:o.filter(d=>d.camada==="DESCONHECIDA").map(d=>({title:`R${d.routeId} · Entregador ${d.entregadorId}`,meta:`${d.statusRota} · ${d.paradas} parada(s) lida(s)`})),emptyMessage:"Todas as rotas vieram com camada reconhecivel."}],layerWarnings:r,eventBuckets:[{title:"Excecoes para agir",tone:s>0?"danger":"ok",summary:s>0?`${s} evento(s) merecem triagem imediata.`:"Sem falha ou cancelamento na janela recente.",cards:(a?.eventos?.eventos??[]).filter(d=>B(d)==="danger").slice(0,5).map(Re),emptyMessage:"Nenhuma excecao recente."},{title:"Fluxo operacional",tone:"info",summary:"Rotas iniciadas, entregas concluidas e movimentos normais da operacao.",cards:(a?.eventos?.eventos??[]).filter(d=>{const I=B(d);return I==="info"||I==="ok"}).slice(0,5).map(Re),emptyMessage:"Nenhum movimento recente de rota ou entrega."}],routeCards:o.map(d=>({title:`R${d.routeId} · Entregador ${d.entregadorId}`,badgeLabel:d.camada,badgeTone:d.camada==="PRIMARIA"?"ok":d.camada==="SECUNDARIA"?"info":"warn",summary:`${d.statusRota} · ${d.paradas} parada(s) · ${d.pontosTrajeto} ponto(s) no trajeto · ${d.quantidadeGaloes} galao(oes)`,tags:d.tags.length>0?d.tags:["sem paradas detalhadas"]})),routeSummary:o.length>0?`${o.filter(d=>d.camada==="PRIMARIA").length} primaria(s), ${o.filter(d=>d.camada==="SECUNDARIA").length} secundaria(s) e ${o.reduce((d,I)=>d+I.paradas,0)} parada(s) mapeada(s).`:"Sem rotas materializadas no mapa operacional.",mapDeposit:a?.mapa?`Deposito ${a.mapa.deposito.lat.toFixed(4)}, ${a.mapa.deposito.lon.toFixed(4)}`:null,action:{title:S?"Acao recomendada agora":"Acao operacional protegida",tone:e.despacho.routeStart.status==="loading"?"warn":S?"info":"warn",badgeLabel:e.despacho.routeStart.status==="loading"?"executando":S?"liberado":"bloqueado",detail:S&&E?`Iniciar R${E.routeId} do entregador ${E.entregadorId} para transformar a secundaria em frota ativa.`:E?`Existe candidata R${E.routeId}, mas o contexto ainda nao esta seguro para girar.`:"Sem candidata confiavel para o proximo giro de frota.",supportingText:S?"O gatilho usa o endpoint operacional real de inicio de rota pronta.":"A saida manual fica bloqueada quando a leitura esta parcial ou quando as camadas nao estao consistentes.",buttonLabel:e.despacho.routeStart.status==="loading"?"Iniciando rota...":S&&E?`Iniciar R${E.routeId}`:"Aguardando contexto seguro",enabled:S,entregadorId:E?.entregadorId??null,blocker:S?null:$[0]??null},notices:[...a?.partialErrors.length?[{label:"Leitura parcial",body:a.partialErrors.join(" | "),tone:"warn"}]:[],...e.sync.lastError?[{label:"Ultimo erro de sincronizacao",body:e.sync.lastError,tone:"danger"}]:[],...e.despacho.lastRouteStart?[{label:e.despacho.lastRouteStart.title,body:e.despacho.lastRouteStart.detail,tone:e.despacho.lastRouteStart.tone}]:[]]}}function Jt(e){const a=String(e||"").toUpperCase();return a==="EM_ANDAMENTO"?"ok":a==="PLANEJADA"?"warn":"muted"}function He(e){const a=String(e||"").toUpperCase();return a==="EM_EXECUCAO"?"info":a==="ENTREGUE"?"ok":a==="FALHOU"||a==="CANCELADA"?"danger":"warn"}function z(e,a){return`
    <article class="metric-card">
      <p class="metric-label">${i(e)}</p>
      <p class="metric-value">${i(a)}</p>
    </article>
  `}function Vt(e,a){const t=String(e.status).toUpperCase()==="EM_EXECUCAO"&&!a;return`
    <article class="stop-card">
      <div class="stop-card-header">
        <div>
          <strong>Parada ${i(e.ordemNaRota)} · Pedido #${i(e.pedidoId)}</strong>
          <p class="mono">Entrega ${i(e.entregaId)} · ${i(e.quantidadeGaloes)} galao(oes)</p>
        </div>
        ${f(e.status,He(e.status))}
      </div>
      <p class="stop-client">${i(e.clienteNome||"Cliente sem nome cadastrado")}</p>
      <div class="stop-actions">
        <button
          class="button primary"
          type="button"
          data-action="run-entregador-event"
          data-event-type="PEDIDO_ENTREGUE"
          data-entrega-id="${i(e.entregaId)}"
          ${t?"":"disabled"}
        >
          Confirmar entrega
        </button>
        <button
          class="button secondary"
          type="button"
          data-action="run-entregador-event"
          data-event-type="PEDIDO_FALHOU"
          data-entrega-id="${i(e.entregaId)}"
          ${t?"":"disabled"}
        >
          Marcar falha
        </button>
        <button
          class="button ghost danger-text"
          type="button"
          data-action="run-entregador-event"
          data-event-type="PEDIDO_CANCELADO"
          data-entrega-id="${i(e.entregaId)}"
          ${t?"":"disabled"}
        >
          Cancelar pedido
        </button>
      </div>
    </article>
  `}function Xt(e){return`
    <article class="history-card">
      <div class="stop-card-header">
        <strong>Pedido #${i(e.pedidoId)}</strong>
        ${f(e.status,He(e.status))}
      </div>
      <p>${i(e.clienteNome||"Cliente sem nome cadastrado")}</p>
      <p class="mono">Entrega ${i(e.entregaId)} · parada ${i(e.ordemNaRota)}</p>
    </article>
  `}function Kt(e,a){const t=e.entregador,n=t.roteiro,o=n?.rota??null,r=Da(n),s=we(t.entregadorId,a),A=o?.status??"SEM_ROTA",b=Jt(A),E=t.action.status==="loading";return`
    <section class="entregador-stack">
      <section class="panel entregador-panel entregador-hero">
        <div class="panel-header">
          <div>
            <h2>Roteiro do entregador</h2>
            <p class="section-copy">Fluxo enxuto para rua: abrir rota, concluir parada e registrar excecao sem navegar por telas pesadas.</p>
          </div>
          ${f(A,b)}
        </div>

        <div class="entregador-control-grid">
          <label class="field">
            <span>Entregador ID</span>
            <input
              id="entregador-id-input"
              type="number"
              min="1"
              value="${i(t.entregadorId)}"
              inputmode="numeric"
            />
          </label>
          <div class="link-card">
            <p class="label">Deep link</p>
            <a class="deep-link" href="${i(s)}">${i(s)}</a>
          </div>
        </div>

        <div class="toolbar-actions compact">
          <button class="button secondary" type="button" data-action="load-entregador">Carregar roteiro</button>
          <button class="button ghost" type="button" data-action="copy-entregador-link">Copiar link</button>
          <button
            class="button primary"
            type="button"
            data-action="start-entregador-route"
            ${o&&o.status==="PLANEJADA"&&!E?"":"disabled"}
          >
            Iniciar rota pronta
          </button>
        </div>

        <div class="metrics-grid compact">
          ${z("Rota",o?`R${o.rotaId}`:"Sem rota")}
          ${z("Carga remanescente",`${n?.cargaRemanescente??0} galao(oes)`)}
          ${z("Concluidas",String(r.concluidas))}
          ${z("Progresso",`${r.percentualConcluido}%`)}
        </div>

        ${r.proximaParada?`
              <div class="notice notice-info">
                <strong>Proxima parada:</strong> pedido #${i(r.proximaParada.pedidoId)} ·
                ${i(r.proximaParada.clienteNome||"cliente sem nome")} ·
                ${i(r.proximaParada.quantidadeGaloes)} galao(oes)
              </div>
            `:""}

        ${t.sync.lastError?`<div class="notice notice-danger"><strong>Ultimo erro:</strong> ${i(t.sync.lastError)}</div>`:""}

        ${t.lastAction?`
              <div class="notice notice-${t.lastAction.tone}">
                <strong>${i(t.lastAction.title)}:</strong> ${i(t.lastAction.detail)}
                ${t.lastAction.payload?`<p class="mono action-payload">${i(JSON.stringify(t.lastAction.payload))}</p>`:""}
              </div>
            `:""}
      </section>

      <section class="entregador-grid">
        <section class="panel entregador-panel">
          <div class="panel-header">
            <div>
              <h3>Paradas ativas</h3>
              <p class="section-copy">Acoes terminais so ficam habilitadas quando a parada estiver em execucao no backend.</p>
            </div>
            ${f(String(r.pendentes),"info")}
          </div>
          <div class="stop-list">
            ${n&&n.paradasPendentesExecucao.length>0?n.paradasPendentesExecucao.map($=>Vt($,E)).join(""):'<p class="empty-copy">Nenhuma parada pendente/em execucao para este entregador.</p>'}
          </div>
        </section>

        <section class="panel entregador-panel">
          <div class="panel-header">
            <div>
              <h3>Fechadas nesta rota</h3>
              <p class="section-copy">Historico rapido para validar o que ja saiu da rua.</p>
            </div>
            ${f(String(r.concluidas),"ok")}
          </div>
          <div class="history-list">
            ${n&&n.paradasConcluidas.length>0?n.paradasConcluidas.map(Xt).join(""):'<p class="empty-copy">Nenhuma parada concluida ainda.</p>'}
          </div>
        </section>
      </section>

      <section class="panel entregador-panel">
        <div class="panel-header">
          <div>
            <h3>Leitura operacional</h3>
            <p class="section-copy">Use o endpoint dedicado de inicio de rota para abrir a proxima carga planejada do entregador. Depois disso, conclua ou registre excecao direto nas paradas em execucao.</p>
          </div>
          ${f(t.fetchedAt?P(t.fetchedAt):"sem sincronizacao","muted")}
        </div>
        <div class="entregador-readout">
          <article class="readout-card">
            <p class="label">Status da sincronizacao</p>
            <p>${i(t.sync.status)}</p>
          </article>
          <article class="readout-card">
            <p class="label">Total de paradas</p>
            <p>${i(r.totalParadas)}</p>
          </article>
          <article class="readout-card">
            <p class="label">Rota ativa</p>
            <p>${o?`R${i(o.rotaId)} · ${i(o.status)}`:"Sem rota hoje"}</p>
          </article>
        </div>
      </section>
    </section>
  `}function Wt(e){return`
    <section class="panel module-nav">
      <div class="panel-header">
        <div>
          <h2>Fluxos da operacao</h2>
          <p class="section-copy">Cada modulo organiza a mesma operacao sob a otica do papel que esta trabalhando agora.</p>
        </div>
        ${f(le(e.activeModule).label,"info")}
      </div>
      <div class="module-tab-list">
        ${de.map(a=>`
              <button
                class="module-tab ${a.id===e.activeModule?"is-active":""}"
                type="button"
                data-action="navigate"
                data-module-id="${i(a.id)}"
              >
                <div class="module-tab-topline">
                  <strong>${i(a.label)}</strong>
                  ${f(a.status==="active"?"ativo":"planejado",a.status==="active"?"ok":"muted")}
                </div>
                <p>${i(a.description)}</p>
              </button>
            `).join("")}
      </div>
    </section>
  `}function Qt(e){return e.activeModule==="despacho"?Lt(Gt(e)):e.activeModule==="atendimento"?Xa(e):e.activeModule==="entregador"?Kt(e,window.location.href):ot(Rt(e))}function _e(e,a){const t=le(a.activeModule),n=a.snapshot?.partialErrors.length??0;e.innerHTML=`
    <div class="app-shell">
      <header class="hero">
        <div class="hero-copy-block">
          <p class="eyebrow">Agua Viva</p>
          <h1>Operacao web por papel</h1>
          <p class="hero-copy">${i(t.description)}</p>
          <div class="hero-meta">
            ${f("dados reais","info")}
            ${f(a.connection.autoRefresh?"auto-refresh ligado":"auto-refresh desligado",a.connection.autoRefresh?"ok":"muted")}
            ${f(a.snapshot?.fetchedAt?P(a.snapshot.fetchedAt):"sem sincronizacao","muted")}
          </div>
        </div>
        <div class="hero-signals">
          <article class="signal-card">
            <p class="label">Modulo ativo</p>
            <strong>${i(t.label)}</strong>
            <p>${i(t.description)}</p>
          </article>
          <article class="signal-card">
            <p class="label">Sincronizacao</p>
            <strong>${i(a.sync.status)}</strong>
            <p>${i(a.sync.lastError||"Leitura operacional sem erro nesta rodada.")}</p>
          </article>
          <article class="signal-card">
            <p class="label">Leitura parcial</p>
            <strong>${i(String(n))}</strong>
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
              <input id="api-base-input" type="text" value="${i(a.connection.apiBaseDraft)}" spellcheck="false" />
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

      ${Wt(a)}
      ${Qt(a)}
    </div>
  `}const Ge="agua-viva.operacao-web.api-base",Je="agua-viva.operacao-web.auto-refresh",Ve="agua-viva.operacao-web.atendimento-state",Xe="agua-viva.operacao-web.entregador-id",Yt="http://localhost:8082";function V(e){try{return window.localStorage.getItem(e)}catch{return null}}function X(e,a){try{window.localStorage.setItem(e,a)}catch{}}function Zt(e){const a=V(e);if(!a)return null;try{return JSON.parse(a)}catch{return null}}function en(){return V(Ge)||Yt}function an(e){X(Ge,e)}function tn(){return V(Je)!=="0"}function nn(e){X(Je,e?"1":"0")}function on(){return Zt(Ve)}function rn(e){X(Ve,JSON.stringify(e))}function sn(){const e=Number(V(Xe));return Number.isInteger(e)&&e>0?e:null}function dn(e){Number.isInteger(e)&&e>0&&X(Xe,String(e))}const ln=15e3,cn=1,Ke=document.querySelector("#app");if(!Ke)throw new Error("Elemento #app nao encontrado.");const ue=Ke,xe=en(),un=mn()??sn()??cn,pn={activeModule:ae(window.location.hash),connection:{apiBase:xe,apiBaseDraft:xe,autoRefresh:tn()},sync:{status:"idle",lastError:null},snapshot:null,atendimento:sa(on()),despacho:{routeStart:{status:"idle",lastError:null},lastRouteStart:null},entregador:{entregadorId:un,roteiro:null,fetchedAt:null,sync:{status:"idle",lastError:null},action:{status:"idle",lastError:null},lastAction:null}},L=Ua(pn),We=Fa(e=>{L.setActiveModule(e)});let O;const pe=wa({intervalMs:ln,onTick:()=>{O.refreshSnapshot()}});O=Oa({root:ue,router:We,store:L,persistApiBase:an,persistAutoRefresh:nn,persistAtendimentoState:rn,persistEntregadorId:dn});L.subscribe(e=>{_e(ue,e),pe.sync(e.connection.autoRefresh)});O.bind();_e(ue,L.getState());We.start();pe.sync(L.getState().connection.autoRefresh);O.refreshSnapshot();window.addEventListener("beforeunload",()=>{O.dispose(),pe.stop()});function mn(){try{const e=new URL(window.location.href),a=Number(e.searchParams.get("entregadorId"));return Number.isInteger(a)&&a>0?a:null}catch{return null}}
