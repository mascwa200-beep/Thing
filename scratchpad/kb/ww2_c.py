# -*- coding: utf-8 -*-
import json
S=[]
def sec(h,b): S.append({"heading":h,"body":" ".join(b.split())})

sec("Things commonly got wrong about this war", """
Popular memory of this war is unusually confident and unusually inaccurate, partly because so much of
it comes from films and partly because the first generation of accounts was written by participants
with reputations to manage.

**That Germany fought a modern mechanised war.** The famous images are of tank columns, and they were
the spearhead. Behind them the great majority of German infantry walked, and supply ran on horses —
the army used something like 2.75 million of them over the war, and was still using them in 1945. The
United States, not Germany, fought the most thoroughly motorised campaign of the war.

**That Blitzkrieg was a doctrine.** It was largely a journalists' word. What the German army actually
had was a tradition of mission-based command that told subordinates the objective and let them find
the method, combined with radios in every tank at a time when other armies had them only in command
vehicles. When opponents adopted both, the advantage evaporated.

**That Hitler's meddling cost Germany the war and his generals would have won it.** This was
constructed after the war by German commanders writing memoirs for a Western audience, at a moment
when West Germany was being rearmed as an ally and there was every incentive to locate all guilt in a
dead man. Hitler made serious errors. So did his generals, who also planned and executed the war of
annihilation in the east and knew exactly what they were doing.

**That the war began in 1939.** It began in 1937 for China, arguably 1931 with Manchuria, and 1935 for
Ethiopia. The 1939 date is a European convention.

**That D-Day was the decisive blow.** It was necessary and it was magnificently executed, and by June
1944 the German army had already been broken in the east. Three-quarters of German army casualties
were inflicted by the Soviet Union.

**That the Holocaust was secret.** Its full industrial machinery was concealed, but mass shootings
were witnessed by ordinary soldiers who wrote home about them, deportations happened in public, and
Allied governments were publicly condemning the extermination of European Jewry by December 1942.

**That the party's name proves the Nazis were socialists.** The regime destroyed the trade unions,
murdered or imprisoned the communists and social democrats, and privatised state assets. Names of
political parties are marketing, not classification.

**That appeasement was simple cowardice.** It was a policy, held by people with living memory of the
Somme, pursued while Britain rearmed as fast as its industry allowed. It was still wrong, and
understanding why sensible people held it is more useful than assuming they were fools.
""")
print(json.dumps(S, ensure_ascii=False))
