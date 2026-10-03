import re
from pathlib import Path
b=Path(__file__).resolve().parent.parent
doc=(b/'GRAMATICA_CANONICA.md').read_text()
java=(b/'untitled/src/gramatica/Gramatica.java').read_text()
def nombre(x):
 if x == 'SentenciaTrasPrimerId': return 'SENTENCIA_TRAS_ID'
 return re.sub(r'(?<=[a-z])(?=[A-Z])', '_', x).upper()
reglas={}
for bloque in re.findall(r'```bnf\n(.*?)```',doc,re.S):
 for linea in bloque.splitlines():
  if '->' in linea:
   lhs,rhs=linea.split('->',1); lhs=nombre(lhs.strip()); reglas.setdefault(lhs,[]).append(rhs)
  elif linea.strip(): reglas[lhs][-1]+=' '+linea.strip()
def expandir(rhs):
 toks=re.findall(r"'[^']*'|[A-Za-z][A-Za-z0-9_]*|ε|[()|]",rhs)
 i=0
 def alternativas():
  nonlocal i
  alts=[]; seq=[()]
  while i<len(toks) and toks[i]!=')':
   t=toks[i];i+=1
   if t=='|': alts+=seq;seq=[()];continue
   if t=='(':
    grupo=alternativas();assert toks[i]==')';i+=1
   elif t=='ε': grupo=[()]
   else: grupo=[(t[1:-1] if t.startswith("'") else nombre(t),)]
   seq=[a+c for a in seq for c in grupo]
  return alts+seq
 return set(alternativas())
md={k:set().union(*(expandir(v) for v in vs)) for k,vs in reglas.items()}
# Las producciones ejecutables ya están expandidas y separadas por espacios.
js={}
for prod in re.findall(r'"([^"]* → [^"]*)"',java):
 lhs,rhs=prod.split(' → '); lhs=lhs.split()[-1]
 js.setdefault(lhs,set()).update(tuple(x.split()) if x!='ε' else () for x in rhs.split(' | '))
assert md.keys()==js.keys(), (md.keys()-js.keys(),js.keys()-md.keys())
for k in md:
 assert md[k]==js[k], (k,md[k]-js[k],js[k]-md[k])
print('OK: las',len(md),'producciones del documento y Gramatica.java describen las mismas alternativas.')
