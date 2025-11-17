package com.stormunblessed

import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.VidStack

class PelisplusUpnsPro : VidStack() {
    override var mainUrl = "https://pelisplus.upns.pro"
    override var name = "PelisplusUpns"
}
class PelisplusUpnsPro2 : VidStack() {
    override var mainUrl = "https://pelisplus.strp2p.com"
    override var name = "PelisplusStrp2p"
}
class PelisplusUpnsPro3 : VidStack() {
    override var mainUrl = "https://pelisplusto.4meplayer.pro"
    override var name = "Pelisplus4meplayer"
}
class EmturbovidCom : Filesim() {
    override var mainUrl = "https://emturbovid.com"
    override var name = "Emturbovid"
}