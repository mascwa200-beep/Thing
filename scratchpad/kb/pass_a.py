# -*- coding: utf-8 -*-
import json
S=[]
def sec(h,b): S.append({"heading":h,"body":" ".join(b.split())})

sec("What a passport actually is, and the thing it does not do", """
A passport is a document in which one government asserts that you are its national and asks other
governments to let you pass. The formal request is usually printed inside the front cover in some
version of the old wording: that the bearer be allowed to pass freely without let or hindrance. Read
carefully, that is a request, and the distinction is the single most useful thing to understand about
international travel. Your passport establishes who you are and which state will take you back. It
does not entitle you to enter anywhere else. The decision to admit you belongs entirely to the
country you are trying to enter, is taken by an officer at the border, and is taken after you have
already bought the ticket and flown there.

Almost every passport in the world now follows a common technical standard set by the International
Civil Aviation Organization, which is why they are all roughly the same size and why the data page
looks similar whatever language it is in. Along the bottom of that page sit two lines of characters
in a blocky typeface studded with chevrons — the machine-readable zone, which encodes your name,
document number, nationality, date of birth, sex and expiry with check digits, so a scanner can read
it in a moment and detect a mis-scan.

Most passports issued in the last fifteen years are also biometric, marked by a small rectangular
symbol on the cover. Inside is a contactless chip holding the same data as the printed page plus a
facial image, cryptographically signed by the issuing state so that a border system can verify the
document has not been altered. This is what makes automated e-gates possible: the gate reads the
chip, photographs your face, and compares the two.

A few practical consequences follow. The chip is delicate and lives in the cover, so bending the
passport hard, sitting on it, or putting it through a wash can break it, and a broken chip means
manual processing at every border and refusal at some. The laminate over the photograph is a security
feature, and lifting or bubbling laminate is read as evidence of tampering. Water damage that makes
the printed page ambiguous can invalidate the document outright.

It is also worth knowing what a passport is not. It is not proof of residence, and it is not a
driving licence. It generally remains the property of the issuing government rather than of you,
which is the legal basis on which a state can cancel or refuse to renew one. And it is not the only
travel document in existence: refugees may hold a Convention travel document, stateless people a
similar one, and seafarers and some international officials travel on their own specialised papers.
For most people, most of the time, the passport is the whole of it — and the correct mental model is
that you are carrying a request, not a right.
""")

sec("Getting one: the application, the photograph, and the wait", """
The process differs in detail everywhere and is the same in outline. You prove your identity and your
nationality, you supply a compliant photograph, you pay a fee, and you wait.

Proving nationality usually means a birth certificate, a naturalisation certificate, or a previous
passport. First applications are much more demanding than renewals, because the state is establishing
the claim for the first time rather than confirming one it already accepted; expect to supply parents'
details, and be prepared for the process to take considerably longer. Many countries also want a
countersignature — someone of a recognised profession or standing who has known you for some years,
who confirms the photograph is of you. Choose that person for reachability rather than status: the
passport office may telephone them, and an unreachable countersignatory stalls the application
entirely.

The photograph causes more rejections than anything else, and the rules are more particular than
people expect. Plain light background, no shadows, the whole head visible from the crown to the
shoulders and squarely facing the camera, a neutral expression with the mouth closed, and eyes open
and unobscured. Most countries now want glasses removed, because reflections and frames defeat facial
recognition. Head coverings are generally permitted for religious reasons provided the face is clear
from the bottom of the chin to the top of the forehead. Photographs of infants are exempted from some
of this by necessity, but a sleeping baby with closed eyes will still be rejected in many
jurisdictions. If a booth or an app offers to check compliance, use it; a rejected photograph costs
you weeks, not minutes.

Processing times are the part people misjudge. Routine service is often quoted in weeks and can
stretch far beyond that when demand surges — as it did worldwide when travel resumed after the
pandemic, with some countries quoting several months. Expedited and same-day services exist in many
countries at a premium and usually require you to attend in person. The practical rule is to treat a
passport as something you renew when it has a year left on it, not when you have a trip booked.

Two further points. Children's passports are typically valid for a shorter period than adults' —
often five years rather than ten — because children's faces change, and parents routinely forget this
and discover it at the airport. And when you renew, check whether your country carries unused
validity over to the new document. Several used to add up to nine months and then stopped, and
travellers who assumed the old rule found themselves short against destination validity requirements
that they had calculated correctly under the wrong assumption.
""")

sec("The validity rules that catch people out", """
Almost everyone who is turned away at check-in has a valid passport. What they do not have is a
passport that satisfies the destination's rules, and there are four of these that account for the
great majority of refusals.

**The six-month rule.** Very many countries require your passport to remain valid for at least six
months beyond your intended date of departure from their territory — not beyond your arrival, and not
merely for the duration of the trip. Some require three months, some require only validity for the
stay, and a handful specify six months beyond arrival, which is stricter still. The point is that a
passport expiring in four months is unusable for a large fraction of the world while being, in every
ordinary sense, perfectly valid. Airlines check this at check-in because they are the ones who must
fly you home at their own cost if you are refused, and they will simply decline to board you.

**Blank pages.** Countries that stamp on entry may require one, two, or occasionally four completely
blank pages, and some specify pages headed for visas rather than any blank page. Frequent travellers
run out of room years before the document expires. Some countries once issued extra pages; most have
stopped, so the answer now is a new passport.

**Damage.** A passport is refused for damage far more readily than people expect. Lifting laminate,
a torn or missing page, water staining that obscures the data page, a broken chip, or writing added
by anyone other than the issuing authority can all invalidate it. Border officers are trained to
treat physical irregularity as possible tampering, and they are not obliged to give you the benefit
of the doubt. If your passport has been through anything, replace it before you travel rather than
finding out at a desk.

**The name.** The name on your ticket must match the name in your passport, and the machine-readable
zone is what matters, not the fancy printed version. Middle names, hyphens, accented characters,
married names not yet updated and the transliteration of non-Latin scripts all cause problems. Book
tickets exactly as the document reads.

Two habits remove nearly all of this risk. First, check the destination's requirements on the
destination government's own website, not on a travel blog and not on an airline's summary page —
rules change, and only the issuing authority is authoritative. Second, look at your passport's expiry
date at the start of every year rather than the start of every trip. Renewal is slow, refusal is
immediate, and the two problems meet at the check-in desk.
""")

sec("Visas: what they are, who decides, and the classes you will meet", """
A visa is a separate permission, issued in advance by the country you want to visit, indicating that
you have been pre-screened and may travel to their border to ask for entry. That last clause matters
and surprises people: in most legal systems a visa is not itself entry permission. It is permission
to present yourself and be considered. The officer at the border makes the actual decision, and can
refuse a person holding a perfectly genuine visa.

Visas are granted by class, and the class defines what you may do, not just how long you may stay.
Applying under the wrong one is a serious matter rather than a technicality.

**Visitor or tourist** visas cover holidays and visiting family and almost always forbid working,
including remote work for a foreign employer in some jurisdictions, and studying beyond short
courses. **Business** visas cover meetings, negotiations and conferences but generally not productive
work for a local entity. **Transit** visas are needed by some nationalities merely to change planes,
which catches travellers who assumed an airport is not really the country. **Student** visas require
an offer from an accredited institution and usually cap paid work. **Work** visas normally require an
employer to sponsor you and to have demonstrated that the role could not be filled locally.
**Family and settlement** visas are the slowest and most document-heavy of all.

Applications ask for proof that you will leave again — return tickets, employment, property, family
ties — because the thing consular officers are assessing is not really your holiday but your
incentive to go home. They also ask for proof of funds, accommodation, and sometimes travel
insurance. Refusals are common, are often given with minimal explanation, and in most countries carry
no meaningful right of appeal. A previous refusal must generally be declared on every future
application, everywhere, and concealing one is far worse than having one.

Where and how you apply varies: an embassy or consulate in person, an outsourced visa centre, an
online e-visa portal, or on arrival at the border for some nationalities and destinations. Visa on
arrival is the least reliable of these, because it depends on the desk being staffed, the system
being up, and you having the exact fee, often in cash and often in a specific currency.

The uncomfortable truth underneath all of this is that the passport you happen to hold determines how
much of the world you can reach easily. Holders of some passports can visit most countries without
applying for anything; holders of others must apply, pay and wait for nearly every journey. This is
not a reflection of the traveller, and it is the single largest practical inequality in international
movement.
""")

sec("Visa-free travel, and the electronic permissions that are not quite visas", """
Many journeys need no visa at all, under bilateral or regional arrangements that let nationals of one
country visit another for a limited period without applying in advance. Visa-free is not
condition-free: it comes with a maximum stay, a permitted purpose that is almost always tourism or
business rather than work, and often a requirement to hold an onward ticket and sufficient funds.

The rule most often misunderstood is the rolling window. The Schengen area of Europe allows most
visa-free visitors ninety days within any one hundred and eighty day period — and the window rolls
continuously rather than resetting on a calendar date or on leaving and returning. On any given day
the calculation looks back one hundred and eighty days and adds up every day you were present. People
who take short frequent trips exhaust the allowance without ever staying long, discover it at a
border, and receive an overstay record. Official calculators exist and are worth using if you travel
to the region often.

A newer category sits between visa-free and visa: the electronic travel authorisation. These are
pre-travel registrations, applied for online, usually cheap, usually approved within minutes to days,
checked by the airline before boarding, and tied to your passport. The United States has required
ESTA from visa-waiver nationals for many years, Canada has eTA, and the United Kingdom and the
European Union have each been rolling out their own schemes. They are not visas and their approval
does not guarantee entry, but travelling without one when it is required means being refused at
check-in, at your own cost.

Three practical points about them. They are attached to a specific passport, so renewing your
passport invalidates the authorisation even if it has time left. They have their own validity period
independent of the trip, commonly a couple of years or until the passport expires, whichever comes
first. And they attract a large industry of unofficial sites that charge many times the government
fee for filling in the same form — always apply through the official government domain, which the
destination's own website will link to.

Two other statuses are worth knowing. Some countries operate visa-free or simplified transit for
passengers who do not leave the airport, and some offer generous transit visas that let you spend a
day or two in the city between flights. And certain relationships — the Common Travel Area between
Britain and Ireland, free movement within regional blocs, or agreements between neighbouring states —
create rights that go well beyond ordinary visa-free entry for the people who hold them. If you think
you might qualify for one of these, check, because the difference in what you are permitted to do is
substantial.
""")

sec("Before you go: the preparation that costs an hour and saves a trip", """
Most travel problems are cheap to prevent and expensive to fix from a foreign airport at two in the
morning. The following is the short list that repays the effort.

Check the destination's entry requirements on the destination government's own website, and check
them again a fortnight before departure. Rules move, sometimes at short notice. If your journey has a
connection, check the transit requirements of the country you connect through, which are separate and
are the commonest oversight in the whole of travel.

Photograph or scan your passport data page, your visa or authorisation, your travel insurance policy
and your tickets, and store them somewhere you can reach without your phone and without your bag —
an email to yourself, or a cloud folder you can open from a borrowed computer. Leave a copy with
someone at home. If your passport goes missing, having the number, issue date and place of issue
turns a difficult week into an ordinary one. A paper photocopy carried separately from the passport
is worth its weight for the same reason.

Register with your government's traveller service if it offers one, so that a consulate knows you are
in the country in the event of a crisis. Read your government's travel advice for the destination —
not for the alarm, but because insurance policies frequently void cover for travel against official
advice, which is a clause people discover at the point of claim.

Carry medication in its original labelled packaging with the pharmacy label intact, and bring a copy
of the prescription or a letter from your doctor giving the generic drug name. This is not
bureaucratic caution. Medicines that are ordinary at home are controlled substances elsewhere:
codeine-containing painkillers, some strong cold remedies, and common stimulant medications
prescribed for attention disorders are all restricted or prohibited in particular countries, and
people have been detained over a blister pack in a wash bag. Check the destination's rules on your
own medicines specifically.

Know your money limits. Most countries require you to declare cash above a threshold — commonly the
equivalent of ten thousand euros or dollars — counting everything you carry, including money
belonging to travelling companions in some interpretations. Failing to declare is treated as an
offence and can result in seizure of the whole amount.

Finally, take a moment on insurance. Confirm it covers the activities you plan, that the medical
limit is high enough for the country you are visiting, and that it includes repatriation. The single
most expensive thing that can happen to a traveller is a medical evacuation, and it is the one thing
a consulate cannot pay for.
""")

sec("What actually happens at a border", """
Arriving, you join a queue for immigration control, which decides whether you may enter, and then
pass through customs, which decides what may come in with you. These are separate functions, often
run by separate agencies, and it helps to think of them separately.

At immigration you present your passport and, where required, your visa or authorisation and any
arrival card. Increasingly you will be directed to an automated gate that reads your chip and
photographs you; many countries reserve these for their own nationals and residents, and some now
open them to a wider set of visitors. Biometric capture — fingerprints and a facial image — is
routine on arrival in a growing number of countries.

If you meet an officer, expect questions. What is the purpose of your visit, how long are you
staying, where are you staying, what do you do for a living, who are you travelling with, how are you
funding the trip. These are not idle. The officer is checking that your story is consistent with your
visa class, with your documents, and with itself, and inconsistency is what draws attention. Answer
briefly, accurately and without elaboration. Have the address of your first night's accommodation
written down; being unable to say where you are going is a common trigger for further questions.

Do not lie, do not joke, and do not become indignant. Misrepresentation at a border is a serious
matter with long consequences, and officers have wide discretion and no obligation to enjoy your
sense of humour. If you are unsure of an answer, say so plainly.

Being sent to secondary inspection — a side room, a longer interview, a search of your bags or your
phone — is uncomfortable and often means nothing more than a random selection or an unclear document.
Phone and laptop searches at borders are lawful in many countries under powers that do not require
suspicion, and in some you can be required to unlock the device. If that matters to you, the time to
think about it is before you pack, not in the room.

Entry is normally recorded with a stamp, an electronic record, or both, and increasingly by electronic
systems that are replacing stamps altogether. Look at the stamp before you walk away. It records the
date and often the permitted length of stay, and a wrong or missing entry stamp is genuinely
difficult to correct later and can make you appear to have entered illegally. If the stamp is absent
or wrong, say so at the desk while you are still standing at it.

Departure matters too. Some countries record exit and some do not, and where exit is not recorded
the burden of proving you left on time can fall on you. Keep boarding passes.
""")
print(json.dumps(S, ensure_ascii=False))
