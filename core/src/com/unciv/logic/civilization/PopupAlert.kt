package com.unciv.logic.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization

enum class AlertType : IsPartOfGameInfoSerialization {
    Defeated,
    WonderBuilt,
    TechResearched,
    WarDeclaration,
    FirstContact,
    CityConquered,
    CityTraded,
    BorderConflict,
    TilesStolen,

    DemandToStopSettlingCitiesNear,
    CitySettledNearOtherCivDespiteOurPromise,

    DemandToStopSpreadingReligion,
    ReligionSpreadDespiteOurPromise,
    
    DemandToStopSpyingOnUs,
    SpyingOnUsDespiteOurPromise,
    
    DemandToNotAttackUs,
    AttackedUsDespitePromise,
    
    AcceptingDemand,
    RejectingDemand,

    GoldenAge,
    DeclarationOfFriendship,
    StartIntro,
    DiplomaticMarriage,
    BulliedProtectedMinor,
    AttackedProtectedMinor,
    AttackedAllyMinor,
    RecapturedCivilian,
    GameHasBeenWon,
    Event,
    
    Denounced,

    TradeRouteOffer,  // UncivGC 2026-08-26 商路 v2: 国外商路邀请 (value = "fromCityId|toCityId")

    AllianceOffer,   // UncivGC 2026-08-26 同盟 v1.0: 同盟提议 (value = 发起方 civId)
    AllianceRenew,   // 同盟到期续约弹窗 (value = 对方 civId)
    AllianceFollowUp // 盟友宣战/被宣战, 询问是否跟进 (value = 目标 civId)
}

class PopupAlert : IsPartOfGameInfoSerialization {
    lateinit var type: AlertType
    lateinit var value: String

    constructor(type: AlertType, value: String) {
        this.type = type
        this.value = value
    }

    constructor() // for json serialization
}
