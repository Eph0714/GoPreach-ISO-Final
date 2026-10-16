package com.emfitsolutions.gopreach.domain

/**
 * The words the Publisher's Planner reads aloud for a chosen range of months (or one day, week, month or year): the time in the field, the busiest
 * month, and the Return Visits and Bible Studies. Return Visits and Bible Studies are counted per month (the same person is never counted twice in a
 * month), so they are NEVER added across months here - they are read month by month, with the highest and the latest. Months with nothing recorded are
 * said plainly. Every function can speak English, Tagalog or Iloko ([NarrationLanguage]); month names are read as given by the caller.
 */
object PlannerNarration {
    data class Month(val label: String, val minutes: Int, val returnVisits: Int, val bibleStudies: Int)

    /** Spoken length of time: "12 hours and 30 minutes", "1 hour", "45 minutes", "no time". */
    fun duration(minutes: Int, l: NarrationLanguage = NarrationLanguage.ENGLISH): String {
        if (minutes <= 0) return l.pick("no time", "walang oras", "awan ti oras")
        val h = minutes / 60
        val m = minutes % 60
        val hours = l.pick(if (h == 1) "1 hour" else "$h hours", "$h oras", "$h nga oras")
        val mins = l.pick(if (m == 1) "1 minute" else "$m minutes", "$m minuto", "$m a minuto")
        return when {
            h == 0 -> mins
            m == 0 -> hours
            else -> hours + l.pick(" and ", " at ", " ken ") + mins
        }
    }

    private fun months(n: Int, l: NarrationLanguage) = l.pick(if (n == 1) "1 month" else "$n months", "$n buwan", "$n a bulan")
    private fun people(n: Int, l: NarrationLanguage) = l.pick(if (n == 1) "1 person" else "$n people", "$n tao", "$n a tao")
    private fun rv(n: Int, l: NarrationLanguage) = l.pick(if (n == 1) "1 return visit" else "$n return visits", "$n balik-dalaw", "$n nga agsubli a panagbisita")
    private fun bs(n: Int, l: NarrationLanguage) = l.pick(if (n == 1) "1 Bible study" else "$n Bible studies", "$n pag-aaral sa Bibliya", "$n a panagadal iti Biblia")
    private fun hoursCount(n: Int, l: NarrationLanguage) = l.pick(if (n == 1) "1 hour" else "$n hours", "$n oras", "$n nga oras")

    /** One range, e.g. "May 2026 to October 2026". */
    fun script(rangeLabel: String, months: List<Month>, l: NarrationLanguage = NarrationLanguage.ENGLISH): List<String> = buildList {
        if (months.isEmpty()) {
            add(l.pick("Planner summary for $rangeLabel.", "Buod ng planner para sa $rangeLabel.", "Pakabuklan ti planner para iti $rangeLabel."))
            add(l.pick("There is nothing recorded in this range yet.", "Wala pang naitala sa saklaw na ito.", "Awan pay ti naisurat iti daytoy a saklaw."))
            return@buildList
        }
        add(
            l.pick(
                "Planner summary for $rangeLabel, ${months(months.size, l)}.", "Buod ng planner para sa $rangeLabel, ${months(months.size, l)}.",
                "Pakabuklan ti planner para iti $rangeLabel, ${months(months.size, l)}.",
            ),
        )
        val total = months.sumOf { it.minutes }
        if (total <= 0 && months.all { it.returnVisits == 0 && it.bibleStudies == 0 }) {
            add(l.pick("Nothing is recorded in this range yet.", "Wala pang naitala sa saklaw na ito.", "Awan pay ti naisurat iti daytoy a saklaw."))
            return@buildList
        }
        add(l.pick("Total time in the field: ${duration(total, l)}.", "Kabuuang oras sa ministeryo sa field: ${duration(total, l)}.", "Dagup nga oras iti ministerio iti field: ${duration(total, l)}."))
        if (months.size > 1) {
            val avg = duration(total / months.size, l)
            add(l.pick("That is an average of $avg a month.", "Katumbas iyon ng average na $avg bawat buwan.", "Dayta ket agarup $avg iti kada bulan."))
        }
        months.filter { it.minutes > 0 }.maxByOrNull { it.minutes }?.takeIf { months.size > 1 }?.let {
            val t = duration(it.minutes, l)
            add(l.pick("Your busiest month was ${it.label}, with $t.", "Ang pinakaabalang buwan mo ay ${it.label}, na may $t.", "Ti kaaramidan a bulan ket ${it.label}, nga addaan iti $t."))
        }

        add(
            if (months.size == 1) l.pick("Month by month.", "Buwan-buwan.", "Kada bulan.")
            else l.pick("Month by month, oldest first.", "Buwan-buwan, mula sa pinakamatanda.", "Kada bulan, nangrugi iti kadaanan."),
        )
        months.forEach { m ->
            add(
                l.pick(
                    "${m.label}: ${duration(m.minutes, l)}, ${rv(m.returnVisits, l)} and ${bs(m.bibleStudies, l)}.",
                    "${m.label}: ${duration(m.minutes, l)}, ${rv(m.returnVisits, l)} at ${bs(m.bibleStudies, l)}.",
                    "${m.label}: ${duration(m.minutes, l)}, ${rv(m.returnVisits, l)} ken ${bs(m.bibleStudies, l)}.",
                ),
            )
        }
        if (months.size > 1) {
            months.maxByOrNull { it.returnVisits }?.takeIf { it.returnVisits > 0 }?.let {
                add(
                    l.pick(
                        "Most return visits in a month: ${it.returnVisits}, in ${it.label}.", "Pinakamaraming balik-dalaw sa isang buwan: ${it.returnVisits}, noong ${it.label}.",
                        "Kadakkelan nga agsubli a panagbisita iti maysa a bulan: ${it.returnVisits}, idi ${it.label}.",
                    ),
                )
            }
            months.maxByOrNull { it.bibleStudies }?.takeIf { it.bibleStudies > 0 }?.let {
                add(
                    l.pick(
                        "Most Bible studies in a month: ${it.bibleStudies}, in ${it.label}.", "Pinakamaraming pag-aaral sa Bibliya sa isang buwan: ${it.bibleStudies}, noong ${it.label}.",
                        "Kadakkelan a panagadal iti Biblia iti maysa a bulan: ${it.bibleStudies}, idi ${it.label}.",
                    ),
                )
            }
            val last = months.last()
            add(
                l.pick(
                    "In the latest month, ${last.label}, you had ${rv(last.returnVisits, l)} and ${bs(last.bibleStudies, l)}.",
                    "Sa pinakahuling buwan, ${last.label}, mayroon kang ${rv(last.returnVisits, l)} at ${bs(last.bibleStudies, l)}.",
                    "Iti kinaudi a bulan, ${last.label}, addaanka iti ${rv(last.returnVisits, l)} ken ${bs(last.bibleStudies, l)}.",
                ),
            )
        }
        add(
            l.pick(
                "Return visits and Bible studies are counted for each month and are not added together across months.",
                "Binibilang ang balik-dalaw at pag-aaral sa Bibliya sa bawat buwan at hindi pinagsasama sa iba't ibang buwan.",
                "Mabilang dagiti agsubli a panagbisita ken panagadal iti Biblia iti kada bulan ken saan a pagtitipon iti nadumaduma a bulan.",
            ),
        )
    }

    /** What one Planner view (Today, Week, Month or Year) shows for its period. [monthRows] is only given for a Year (the months of that year). */
    data class Period(
        val unit: String,
        val label: String,
        val minutes: Int,
        val creditMinutes: Int,
        val returnVisits: Int,
        val bibleStudies: Int,
        val goalHours: Int,
        val remainingMinutes: Int,
        val surplusMinutes: Int,
        val showHours: Boolean = true,
        val showCredit: Boolean = true,
        val showReturnVisits: Boolean = true,
        val showBibleStudies: Boolean = true,
        val monthRows: List<Month> = emptyList(),
    )

    private fun unitWord(unit: String, l: NarrationLanguage): String = when (unit) {
        "day" -> l.pick("day", "araw", "aldaw")
        "week" -> l.pick("week", "linggo", "semana")
        "month" -> l.pick("month", "buwan", "bulan")
        "year" -> l.pick("year", "taon", "tawen")
        else -> unit
    }

    /** A single day / week / month / year, in the order the screen shows it: time, credit hours, return visits, Bible studies, then the hours goal. */
    fun periodScript(p: Period, l: NarrationLanguage = NarrationLanguage.ENGLISH): List<String> = buildList {
        val u = unitWord(p.unit, l)
        add(l.pick("Your planner for ${p.label}.", "Ang planner mo para sa ${p.label}.", "Ti planner mo para iti ${p.label}."))
        val anyShown = p.showHours || p.showCredit || p.showReturnVisits || p.showBibleStudies
        if (!anyShown) {
            add(
                l.pick(
                    "All planner sections are hidden, so there is nothing to read.", "Nakatago ang lahat ng seksyon ng planner, kaya wala itong mababasa.",
                    "Nailemmeng amin a paset ti planner, isu nga awan ti mabasa.",
                ),
            )
            return@buildList
        }
        val empty = (!p.showHours || p.minutes <= 0) && (!p.showCredit || p.creditMinutes <= 0) &&
            (!p.showReturnVisits || p.returnVisits == 0) && (!p.showBibleStudies || p.bibleStudies == 0)
        if (empty && p.goalHours <= 0) {
            add(l.pick("Nothing is recorded for this $u yet.", "Wala pang naitala para sa $u na ito.", "Awan pay ti naisurat para iti daytoy a $u."))
            return@buildList
        }

        if (p.showHours) add(l.pick("Field service time for the $u: ${duration(p.minutes, l)}.", "Oras sa ministeryo sa field para sa $u: ${duration(p.minutes, l)}.", "Oras iti ministerio iti field para iti $u: ${duration(p.minutes, l)}."))
        if (p.showCredit) {
            add(
                if (p.creditMinutes > 0) l.pick("Credit hours: ${duration(p.creditMinutes, l)}.", "Credit hours: ${duration(p.creditMinutes, l)}.", "Credit hours: ${duration(p.creditMinutes, l)}.")
                else l.pick("No credit hours.", "Walang credit hours.", "Awan ti credit hours."),
            )
        }
        if (p.showReturnVisits) add(l.pick("Return visits: ${people(p.returnVisits, l)}.", "Balik-dalaw: ${people(p.returnVisits, l)}.", "Agsubli a panagbisita: ${people(p.returnVisits, l)}."))
        if (p.showBibleStudies) add(l.pick("Bible studies: ${people(p.bibleStudies, l)}.", "Pag-aaral sa Bibliya: ${people(p.bibleStudies, l)}.", "Panagadal iti Biblia: ${people(p.bibleStudies, l)}."))
        if (p.showHours) {
            val goal = hoursCount(p.goalHours, l)
            when {
                p.goalHours <= 0 -> add(l.pick("No hours goal is set for this $u.", "Walang itinakdang layunin sa oras para sa $u na ito.", "Awan ti naikeddeng a kalat nga oras para iti daytoy a $u."))
                p.surplusMinutes > 0 -> add(
                    l.pick(
                        "Your hours goal is $goal. You have reached it, with ${duration(p.surplusMinutes, l)} more.",
                        "Ang layunin mong oras ay $goal. Naabot mo na ito, at lumampas ng ${duration(p.surplusMinutes, l)}.",
                        "Ti kalat nga oras ket $goal. Naragpatmon, ken nalasatmo iti ${duration(p.surplusMinutes, l)}.",
                    ),
                )
                p.remainingMinutes > 0 -> add(
                    l.pick(
                        "Your hours goal is $goal. ${duration(p.remainingMinutes, l).replaceFirstChar { it.uppercase() }} remaining.",
                        "Ang layunin mong oras ay $goal. May natitira pang ${duration(p.remainingMinutes, l)}.",
                        "Ti kalat nga oras ket $goal. Adda pay nabati a ${duration(p.remainingMinutes, l)}.",
                    ),
                )
                else -> add(l.pick("Your hours goal of $goal is exactly reached.", "Eksaktong naabot ang layunin mong $goal.", "Eksakto a naragpat ti kalat a $goal."))
            }
        }
        if (p.monthRows.size > 1) {
            p.monthRows.filter { it.minutes > 0 }.maxByOrNull { it.minutes }?.let {
                val t = duration(it.minutes, l)
                add(l.pick("Your busiest month was ${it.label}, with $t.", "Ang pinakaabalang buwan mo ay ${it.label}, na may $t.", "Ti kaaramidan a bulan ket ${it.label}, nga addaan iti $t."))
            }
        }
        if (p.showReturnVisits || p.showBibleStudies) {
            add(
                l.pick(
                    "People are counted once for the $u and are not added up from smaller periods.",
                    "Ang mga tao ay binibilang nang isang beses para sa $u at hindi pinagsasama mula sa mas maliliit na panahon.",
                    "Dagiti tao ket mabilang iti maymaysa para iti $u ken saan a naipaset manipud kadagiti babassit a tiempo.",
                ),
            )
        }
    }

    /** Two ranges side by side: totals of time, and the change between them. */
    fun compareScript(labelA: String, a: List<Month>, labelB: String, b: List<Month>, l: NarrationLanguage = NarrationLanguage.ENGLISH): List<String> = buildList {
        add(l.pick("Planner comparison: $labelA, against $labelB.", "Paghahambing ng planner: $labelA, laban sa $labelB.", "Pagsasaruno ti planner: $labelA, maibusor iti $labelB."))
        val ta = a.sumOf { it.minutes }
        val tb = b.sumOf { it.minutes }
        if (a.isEmpty() && b.isEmpty()) {
            add(l.pick("Nothing is recorded in either range yet.", "Wala pang naitala sa alinman sa dalawang saklaw.", "Awan pay ti naisurat iti uray ania kadagiti dua a saklaw."))
            return@buildList
        }
        add(l.pick("$labelA: ${duration(ta, l)} over ${months(a.size, l)}.", "$labelA: ${duration(ta, l)} sa loob ng ${months(a.size, l)}.", "$labelA: ${duration(ta, l)} iti uneg ti ${months(a.size, l)}."))
        add(l.pick("$labelB: ${duration(tb, l)} over ${months(b.size, l)}.", "$labelB: ${duration(tb, l)} sa loob ng ${months(b.size, l)}.", "$labelB: ${duration(tb, l)} iti uneg ti ${months(b.size, l)}."))
        if (a.isNotEmpty() && b.isNotEmpty()) {
            val avgA = ta / a.size
            val avgB = tb / b.size
            add(
                l.pick(
                    "A month averages ${duration(avgA, l)} in the first range and ${duration(avgB, l)} in the second.",
                    "Ang average sa isang buwan ay ${duration(avgA, l)} sa unang saklaw at ${duration(avgB, l)} sa pangalawa.",
                    "Ti agarup iti maysa a bulan ket ${duration(avgA, l)} iti umuna a saklaw ken ${duration(avgB, l)} iti maikadua.",
                ),
            )
            val d = avgB - avgA
            add(
                when {
                    d > 0 -> l.pick("That is ${duration(d, l)} more a month in the second range.", "Higit iyon ng ${duration(d, l)} bawat buwan sa pangalawang saklaw.", "Nasursurok dayta iti ${duration(d, l)} iti kada bulan iti maikadua a saklaw.")
                    d < 0 -> l.pick("That is ${duration(-d, l)} less a month in the second range.", "Kulang iyon ng ${duration(-d, l)} bawat buwan sa pangalawang saklaw.", "Kurang dayta iti ${duration(-d, l)} iti kada bulan iti maikadua a saklaw.")
                    else -> l.pick("The monthly average is the same in both ranges.", "Pareho ang average kada buwan sa dalawang saklaw.", "Agpada ti agarup iti kada bulan iti dua a saklaw.")
                },
            )
            fun avgCount(list: List<Month>, f: (Month) -> Int) = list.sumOf(f).toDouble() / list.size
            fun one(v: Double) = (kotlin.math.round(v * 10) / 10.0).toString()
            val rvA = one(avgCount(a) { it.returnVisits })
            val rvB = one(avgCount(b) { it.returnVisits })
            val bsA = one(avgCount(a) { it.bibleStudies })
            val bsB = one(avgCount(b) { it.bibleStudies })
            add(
                l.pick(
                    "Return visits average $rvA a month in the first range and $rvB in the second.",
                    "Ang average ng balik-dalaw ay $rvA bawat buwan sa unang saklaw at $rvB sa pangalawa.",
                    "Ti agarup nga agsubli a panagbisita ket $rvA iti kada bulan iti umuna ken $rvB iti maikadua.",
                ),
            )
            add(
                l.pick(
                    "Bible studies average $bsA a month in the first range and $bsB in the second.",
                    "Ang average ng pag-aaral sa Bibliya ay $bsA bawat buwan sa unang saklaw at $bsB sa pangalawa.",
                    "Ti agarup a panagadal iti Biblia ket $bsA iti kada bulan iti umuna ken $bsB iti maikadua.",
                ),
            )
        } else {
            add(
                l.pick(
                    "One of the two ranges has no months, so they cannot be compared.", "Walang buwan ang isa sa dalawang saklaw kaya hindi sila maihahambing.",
                    "Awan ti bulan ti maysa kadagiti dua a saklaw isu nga saan a maipada.",
                ),
            )
        }
    }
}
