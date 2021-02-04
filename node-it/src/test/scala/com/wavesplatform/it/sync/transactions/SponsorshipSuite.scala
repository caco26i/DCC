package com.wavesplatform.it.sync.transactions

import com.typesafe.config.Config
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.it.NodeConfigs
import com.wavesplatform.it.api.SyncHttpApi._
import com.wavesplatform.it.api.TransactionInfo
import com.wavesplatform.it.sync._
import com.wavesplatform.it.transactions.BaseTransactionSuiteLike
import com.wavesplatform.it.util._
import com.wavesplatform.state.diffs.FeeValidation
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxVersion
import com.wavesplatform.transaction.assets.SponsorFeeTransaction
import org.scalatest.{Assertion, FreeSpec}

import scala.concurrent.duration._

class SponsorshipSuite extends FreeSpec with BaseTransactionSuiteLike {

  override def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(_.quorum(0))
      .overrideBase(_.preactivatedFeatures((BlockchainFeatures.BlockReward, 1000000)))
      .overrideBase(_.raw("waves.blockchain.custom.functionality.blocks-for-feature-activation=1"))
      .overrideBase(_.raw("waves.blockchain.custom.functionality.feature-check-blocks-period=1"))
      .withDefault(1)
      .withSpecial(1, _.nonMiner)
      .buildNonConflicting()

  private def sponsor = firstKeyPair
  private def alice   = secondKeyPair
  private def bob     = thirdKeyPair

  private lazy val bobAddress = bob.toAddress.toString

  val Waves                                 = 100000000L
  val Token                                 = 100L
  val sponsorAssetTotal                     = 100 * Token
  val minSponsorFee                         = Token
  val TinyFee                               = Token / 2
  val SmallFee                              = Token + Token / 2
  val LargeFee                              = 10 * Token
  var sponsorWavesBalance                   = 0L
  var minerWavesBalance                     = 0L
  var minerWavesBalanceAfterFirstXferTest   = 0L
  var sponsorWavesBalanceAfterFirstXferTest = 0L
  var firstSponsorAssetId: String           = ""
  var secondSponsorAssetId: String          = ""
  var firstTransferTxToAlice: String        = ""
  var secondTransferTxToAlice: String       = ""
  var firstSponsorTxId: String              = ""
  var secondSponsorTxId: String             = ""

  def assertMinAssetFee(txId: String, sponsorship: Long): Assertion = {
    val txInfo = miner.transactionInfo[TransactionInfo](txId)
    assert(txInfo.minSponsoredAssetFee.contains(sponsorship))
  }

  def assertSponsorship(assetId: String, sponsorship: Long): Assertion = {
    val assetInfo = miner.assetsDetails(assetId)
    assert(assetInfo.minSponsoredAssetFee == Some(sponsorship).filter(_ != 0))
  }

  private lazy val aliceAddress: String   = alice.toAddress.toString
  private lazy val sponsorAddress: String = sponsor.toAddress.toString

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    sponsorWavesBalance = miner.balanceDetails(sponsorAddress).effective
    minerWavesBalance = miner.balanceDetails(miner.address).effective
    minerWavesBalanceAfterFirstXferTest = minerWavesBalance + 2 * issueFee + 2 * sponsorReducedFee + 2 * minFee + 2 * FeeValidation.FeeUnit * SmallFee / minSponsorFee
    sponsorWavesBalanceAfterFirstXferTest = sponsorWavesBalance - 2 * issueFee - 2 * sponsorReducedFee - 2 * minFee - 2 * FeeValidation.FeeUnit * SmallFee / minSponsorFee

    firstSponsorAssetId = miner
      .issue(
        sponsor,
        "AssetTxV1",
        "Created by Sponsorship Suite",
        sponsorAssetTotal,
        decimals = 2,
        reissuable = false,
        fee = issueFee,
        waitForTx = true
      )
      .id
    secondSponsorAssetId = miner
      .issue(
        sponsor,
        "AssetTxV2",
        "Created by Sponsorship Suite",
        sponsorAssetTotal,
        decimals = 2,
        reissuable = false,
        fee = issueFee,
        waitForTx = true
      )
      .id

    firstTransferTxToAlice =
      miner.transfer(sponsor, aliceAddress, sponsorAssetTotal / 2, minFee, Some(firstSponsorAssetId), None, waitForTx = true).id
    secondTransferTxToAlice =
      miner.transfer(sponsor, aliceAddress, sponsorAssetTotal / 2, minFee, Some(secondSponsorAssetId), None, waitForTx = true).id
    firstSponsorTxId = miner.sponsorAsset(sponsor, firstSponsorAssetId, baseFee = Token, fee = sponsorReducedFee, version = TxVersion.V1).id
    secondSponsorTxId = miner.sponsorAsset(sponsor, secondSponsorAssetId, baseFee = Token, fee = sponsorReducedFee, version = TxVersion.V2).id
  }

  "Fee in sponsored asset works fine for transaction" - {

    "make assets sponsored" in {
      nodes.waitForHeightAriseAndTxPresent(firstSponsorTxId)
      nodes.waitForHeightAriseAndTxPresent(secondSponsorTxId)
      miner.transactionInfo[TransactionInfo](secondSponsorTxId).chainId shouldBe Some(AddressScheme.current.chainId)

      assertSponsorship(firstSponsorAssetId, 1 * Token)
      assertSponsorship(secondSponsorAssetId, 1 * Token)
      assertMinAssetFee(firstSponsorTxId, 1 * Token)
      assertMinAssetFee(secondSponsorTxId, 1 * Token)
    }

    "check balance before test accounts balances" in {
      for (sponsorAssetId <- Seq(firstSponsorAssetId, secondSponsorAssetId)) {
        miner.assertAssetBalance(sponsorAddress, sponsorAssetId, sponsorAssetTotal / 2)
        miner.assertAssetBalance(aliceAddress, sponsorAssetId, sponsorAssetTotal / 2)

        val assetInfo = miner.portfolio(aliceAddress).balances.filter(_.assetId == sponsorAssetId).head
        assetInfo.minSponsoredAssetFee shouldBe Some(Token)
        assetInfo.sponsorBalance shouldBe Some(miner.balanceDetails(sponsorAddress).effective)
      }
    }

    "sender cannot make transfer" - {
      "invalid tx timestamp" in {
        for (v <- sponsorshipTxSupportedVersions) {
          def invalidTx(timestamp: Long): SponsorFeeTransaction =
            SponsorFeeTransaction
              .selfSigned(
                version = v,
                sponsor,
                IssuedAsset(ByteStr.decodeBase58(firstSponsorAssetId).get),
                Some(SmallFee),
                minFee,
                timestamp + 1.day.toMillis
              )
              .explicitGet()

          val iTx = invalidTx(timestamp = System.currentTimeMillis + 1.day.toMillis)
          assertBadRequestAndResponse(miner.broadcastRequest(iTx.json()), "Transaction timestamp .* is more than .*ms in the future")
        }
      }
    }

    "fee should be written off in issued asset" - {
      "alice transfer sponsored asset to bob using sponsored fee" in {
        val firstTransferTxCustomFeeAlice =
          miner.transfer(alice, bobAddress, 10 * Token, SmallFee, Some(firstSponsorAssetId), Some(firstSponsorAssetId)).id
        val secondTransferTxCustomFeeAlice =
          miner.transfer(alice, bobAddress, 10 * Token, SmallFee, Some(secondSponsorAssetId), Some(secondSponsorAssetId)).id
        nodes.waitForHeightArise()
        nodes.waitForTransaction(firstTransferTxCustomFeeAlice)
        nodes.waitForTransaction(secondTransferTxCustomFeeAlice)

        miner.assertAssetBalance(aliceAddress, firstSponsorAssetId, sponsorAssetTotal / 2 - SmallFee - 10 * Token)
        miner.assertAssetBalance(aliceAddress, secondSponsorAssetId, sponsorAssetTotal / 2 - SmallFee - 10 * Token)
        miner.assertAssetBalance(bobAddress, firstSponsorAssetId, 10 * Token)
        miner.assertAssetBalance(bobAddress, secondSponsorAssetId, 10 * Token)

        val aliceTxs = miner.transactionsByAddress(aliceAddress, 100)
        aliceTxs.size shouldBe 5 //not 4, because there was one more transaction in IntegrationSuiteWithThreeAddresses class
        aliceTxs.count(tx => tx.sender.contains(aliceAddress) || tx.recipient.contains(aliceAddress)) shouldBe 5
        aliceTxs.map(_.id) should contain allElementsOf Seq(
          firstTransferTxToAlice,
          secondTransferTxToAlice,
          firstTransferTxCustomFeeAlice,
          secondTransferTxCustomFeeAlice
        )

        val bobTxs = miner.transactionsByAddress(bobAddress, 100)
        bobTxs.size shouldBe 3
        bobTxs.count(tx => tx.sender.contains(bobAddress) || tx.recipient.contains(bobAddress)) shouldBe 3
        bobTxs.map(_.id) should contain allElementsOf Seq(firstTransferTxCustomFeeAlice, secondTransferTxCustomFeeAlice)
      }

      "check transactions by address" in {
        val minerTxs = miner.transactionsByAddress(miner.address, 100)
        minerTxs.size shouldBe 4

        val sponsorTxs = miner.transactionsByAddress(sponsorAddress, 100)
        sponsorTxs.size shouldBe 9 //TODO: bug?
        sponsorTxs.count(tx => tx.sender.contains(sponsorAddress) || tx.recipient.contains(sponsorAddress)) shouldBe 7
        sponsorTxs.map(_.id) should contain allElementsOf Seq(
          firstSponsorAssetId,
          secondSponsorAssetId,
          firstTransferTxToAlice,
          secondTransferTxToAlice,
          firstSponsorTxId,
          secondSponsorTxId
        )
      }

      "sponsor should receive sponsored asset as fee, waves should be written off" in {
        miner.assertAssetBalance(sponsorAddress, firstSponsorAssetId, sponsorAssetTotal / 2 + SmallFee)
        miner.assertAssetBalance(sponsorAddress, secondSponsorAssetId, sponsorAssetTotal / 2 + SmallFee)
        miner.assertBalances(sponsorAddress, sponsorWavesBalanceAfterFirstXferTest)
      }

      "miner waves balance should be changed" in {
        miner.assertBalances(miner.address, minerWavesBalanceAfterFirstXferTest)
      }
    }

    "assets balance should contain sponsor fee info and sponsor balance" in {
      val sponsorLeaseSomeWaves = miner.lease(sponsor, bobAddress, leasingAmount, leasingFee).id
      nodes.waitForHeightAriseAndTxPresent(sponsorLeaseSomeWaves)
      val sponsorEffectiveBalance   = miner.balanceDetails(sponsorAddress).effective
      val aliceFirstSponsorAssetBalance  = miner.portfolio(aliceAddress).balances.filter(_.assetId == firstSponsorAssetId).head
      val aliceSecondSponsorAssetBalance = miner.portfolio(aliceAddress).balances.filter(_.assetId == secondSponsorAssetId).head
      aliceFirstSponsorAssetBalance.minSponsoredAssetFee shouldBe Some(minSponsorFee)
      aliceSecondSponsorAssetBalance.minSponsoredAssetFee shouldBe Some(minSponsorFee)
      aliceFirstSponsorAssetBalance.sponsorBalance shouldBe Some(sponsorEffectiveBalance)
      aliceSecondSponsorAssetBalance.sponsorBalance shouldBe Some(sponsorEffectiveBalance)
    }

    "waves fee depends on sponsor fee and sponsored token decimals" in {
      val transferTxCustomLargeFeeAlice1 = miner.transfer(alice, bobAddress, 1.waves, LargeFee, None, Some(firstSponsorAssetId)).id
      val transferTxCustomLargeFeeAlice2 = miner.transfer(alice, bobAddress, 1.waves, LargeFee, None, Some(secondSponsorAssetId)).id
      nodes.waitForHeightAriseAndTxPresent(transferTxCustomLargeFeeAlice1)
      nodes.waitForHeightAriseAndTxPresent(transferTxCustomLargeFeeAlice2)

      miner.assertAssetBalance(sponsorAddress, firstSponsorAssetId, sponsorAssetTotal / 2 + SmallFee + LargeFee)
      miner.assertAssetBalance(sponsorAddress, secondSponsorAssetId, sponsorAssetTotal / 2 + SmallFee + LargeFee)
      miner.assertAssetBalance(aliceAddress, firstSponsorAssetId, sponsorAssetTotal / 2 - SmallFee - LargeFee - 10 * Token)
      miner.assertAssetBalance(aliceAddress, secondSponsorAssetId, sponsorAssetTotal / 2 - SmallFee - LargeFee - 10 * Token)
      miner.assertAssetBalance(bobAddress, firstSponsorAssetId, 10 * Token)
      miner.assertAssetBalance(bobAddress, secondSponsorAssetId, 10 * Token)
      miner.assertBalances(
        sponsorAddress,
        sponsorWavesBalanceAfterFirstXferTest - FeeValidation.FeeUnit * 2 * LargeFee / Token - leasingFee,
        sponsorWavesBalanceAfterFirstXferTest - FeeValidation.FeeUnit * 2 * LargeFee / Token - leasingFee - leasingAmount
      )
      miner.assertBalances(miner.address, minerWavesBalanceAfterFirstXferTest + FeeValidation.FeeUnit * 2 * LargeFee / Token + leasingFee)
    }

    "cancel sponsorship" - {

      "cancel" in {
        val cancelFirstSponsorTxId  = miner.cancelSponsorship(sponsor, firstSponsorAssetId, fee = issueFee, version = TxVersion.V1).id
        val cancelSecondSponsorTxId = miner.cancelSponsorship(sponsor, secondSponsorAssetId, fee = issueFee, version = TxVersion.V2).id
        nodes.waitForHeightAriseAndTxPresent(cancelFirstSponsorTxId)
        nodes.waitForHeightAriseAndTxPresent(cancelSecondSponsorTxId)
      }

      "check asset details info" in {
        for (sponsorAssetId <- Seq(firstSponsorAssetId, secondSponsorAssetId)) {
          val assetInfo = miner.portfolio(aliceAddress).balances.filter(_.assetId == sponsorAssetId).head
          assetInfo.minSponsoredAssetFee shouldBe None
          assetInfo.sponsorBalance shouldBe None
        }
      }

      "cannot pay fees in non sponsored assets" in {
        assertBadRequestAndResponse(
          miner.transfer(alice, bobAddress, 10 * Token, fee = 1 * Token, assetId = None, feeAssetId = Some(firstSponsorAssetId)).id,
          s"Asset $firstSponsorAssetId is not sponsored, cannot be used to pay fees"
        )
        assertBadRequestAndResponse(
          miner.transfer(alice, bobAddress, 10 * Token, fee = 1 * Token, assetId = None, feeAssetId = Some(secondSponsorAssetId)).id,
          s"Asset $secondSponsorAssetId is not sponsored, cannot be used to pay fees"
        )
      }

      "check cancel transaction info" in {
        assertSponsorship(firstSponsorAssetId, 0L)
        assertSponsorship(secondSponsorAssetId, 0L)
      }

      "check sponsor and miner balances after cancel" in {
        miner.assertBalances(
          sponsorAddress,
          sponsorWavesBalanceAfterFirstXferTest - FeeValidation.FeeUnit * 2 * LargeFee / Token - leasingFee - 2 * issueFee,
          sponsorWavesBalanceAfterFirstXferTest - FeeValidation.FeeUnit * 2 * LargeFee / Token - leasingFee - leasingAmount - 2 * issueFee
        )
        miner.assertBalances(
          miner.address,
          minerWavesBalanceAfterFirstXferTest + FeeValidation.FeeUnit * 2 * LargeFee / Token + leasingFee + 2 * issueFee
        )
      }

      "cancel sponsorship again" in {
        val cancelSponsorshipTxId1 = miner.cancelSponsorship(sponsor, firstSponsorAssetId, fee = issueFee, version = TxVersion.V1).id
        val cancelSponsorshipTxId2 = miner.cancelSponsorship(sponsor, firstSponsorAssetId, fee = issueFee, version = TxVersion.V2).id
        nodes.waitForHeightArise()
        nodes.waitForTransaction(cancelSponsorshipTxId1)
        nodes.waitForTransaction(cancelSponsorshipTxId2)
      }

    }
    "set sponsopship again" - {

      "set sponsorship and check new asset details, min sponsored fee changed" in {
        val setAssetSponsoredTx1 = miner.sponsorAsset(sponsor, firstSponsorAssetId, fee = issueFee, baseFee = TinyFee, version = TxVersion.V1).id
        val setAssetSponsoredTx2 = miner.sponsorAsset(sponsor, secondSponsorAssetId, fee = issueFee, baseFee = TinyFee, version = TxVersion.V2).id
        nodes.waitForHeightAriseAndTxPresent(setAssetSponsoredTx1)
        nodes.waitForHeightAriseAndTxPresent(setAssetSponsoredTx2)
        for (sponsorAssetId <- Seq(firstSponsorAssetId, secondSponsorAssetId)) {
          val assetInfo = miner.portfolio(aliceAddress).balances.filter(_.assetId == sponsorAssetId).head
          assetInfo.minSponsoredAssetFee shouldBe Some(Token / 2)
          assetInfo.sponsorBalance shouldBe Some(miner.balanceDetails(sponsorAddress).effective)
        }
      }

      "make transfer with new min sponsored fee" in {
        val sponsoredBalance          = miner.balanceDetails(sponsorAddress)
        val sponsorFirstAssetBalance  = miner.assetBalance(sponsorAddress, firstSponsorAssetId).balance
        val sponsorSecondAssetBalance = miner.assetBalance(sponsorAddress, secondSponsorAssetId).balance
        val aliceFirstAssetBalance    = miner.assetBalance(aliceAddress, firstSponsorAssetId).balance
        val aliceSecondAssetBalance   = miner.assetBalance(aliceAddress, secondSponsorAssetId).balance
        val aliceWavesBalance         = miner.balanceDetails(aliceAddress)
        val bobFirstAssetBalance      = miner.assetBalance(bobAddress, firstSponsorAssetId).balance
        val bobSecondAssetBalance     = miner.assetBalance(bobAddress, secondSponsorAssetId).balance
        val bobWavesBalance           = miner.balanceDetails(bobAddress)
        val minerBalance              = miner.balanceDetails(miner.address)
        val minerFirstAssetBalance    = miner.assetBalance(miner.address, firstSponsorAssetId).balance
        val minerSecondAssetBalance   = miner.assetBalance(miner.address, secondSponsorAssetId).balance

        val transferTxCustomFeeAlice1 = miner.transfer(alice, bobAddress, 1.waves, TinyFee, None, Some(firstSponsorAssetId)).id
        val transferTxCustomFeeAlice2 = miner.transfer(alice, bobAddress, 1.waves, TinyFee, None, Some(secondSponsorAssetId)).id
        nodes.waitForHeight(
          math.max(
            miner.waitForTransaction(transferTxCustomFeeAlice1).height,
            miner.waitForTransaction(transferTxCustomFeeAlice2).height
          ) + 2
        )

        val wavesFee = FeeValidation.FeeUnit * 2 * TinyFee / TinyFee
        miner.assertBalances(sponsorAddress, sponsoredBalance.regular - wavesFee, sponsoredBalance.effective - wavesFee)
        miner.assertAssetBalance(sponsorAddress, firstSponsorAssetId, sponsorFirstAssetBalance + TinyFee)
        miner.assertAssetBalance(sponsorAddress, secondSponsorAssetId, sponsorSecondAssetBalance + TinyFee)
        miner.assertAssetBalance(aliceAddress, firstSponsorAssetId, aliceFirstAssetBalance - TinyFee)
        miner.assertAssetBalance(aliceAddress, secondSponsorAssetId, aliceSecondAssetBalance - TinyFee)
        miner.assertBalances(aliceAddress, aliceWavesBalance.regular - 2.waves, aliceWavesBalance.effective - 2.waves)
        miner.assertBalances(bobAddress, bobWavesBalance.regular + 2.waves, bobWavesBalance.effective + 2.waves)
        miner.assertAssetBalance(bobAddress, firstSponsorAssetId, bobFirstAssetBalance)
        miner.assertAssetBalance(bobAddress, secondSponsorAssetId, bobSecondAssetBalance)
        miner.assertBalances(miner.address, minerBalance.effective + wavesFee)
        miner.assertAssetBalance(miner.address, firstSponsorAssetId, minerFirstAssetBalance)
        miner.assertAssetBalance(miner.address, secondSponsorAssetId, minerSecondAssetBalance)
      }

      "change sponsorship fee in active sponsored asset" in {
        val setAssetSponsoredTx1 = miner.sponsorAsset(sponsor, firstSponsorAssetId, fee = issueFee, baseFee = LargeFee, version = TxVersion.V1).id
        val setAssetSponsoredTx2 = miner.sponsorAsset(sponsor, secondSponsorAssetId, fee = issueFee, baseFee = LargeFee, version = TxVersion.V2).id
        nodes.waitForHeightArise()
        nodes.waitForHeightAriseAndTxPresent(setAssetSponsoredTx1)
        nodes.waitForTransaction(setAssetSponsoredTx2)

        for (sponsorAssetId <- Seq(firstSponsorAssetId, secondSponsorAssetId)) {
          val assetInfo = miner.portfolio(aliceAddress).balances.filter(_.assetId == sponsorAssetId).head
          assetInfo.minSponsoredAssetFee shouldBe Some(LargeFee)
          assetInfo.sponsorBalance shouldBe Some(miner.balanceDetails(sponsorAddress).effective)
        }
      }

      "transfer tx sponsored fee is less then new minimal" in {
        assertBadRequestAndResponse(
          miner
            .transfer(sponsor, aliceAddress, 11 * Token, fee = SmallFee, assetId = Some(firstSponsorAssetId), feeAssetId = Some(firstSponsorAssetId))
            .id,
          s"Fee for TransferTransaction \\($SmallFee in ${Some(firstSponsorAssetId).get}\\) does not exceed minimal value of 100000 WAVES or $LargeFee ${Some(firstSponsorAssetId).get}"
        )
        assertBadRequestAndResponse(
          miner
            .transfer(
              sponsor,
              aliceAddress,
              11 * Token,
              fee = SmallFee,
              assetId = Some(secondSponsorAssetId),
              feeAssetId = Some(secondSponsorAssetId)
            )
            .id,
          s"Fee for TransferTransaction \\($SmallFee in ${Some(secondSponsorAssetId).get}\\) does not exceed minimal value of 100000 WAVES or $LargeFee ${Some(secondSponsorAssetId).get}"
        )
      }

      "make transfer with updated min sponsored fee" in {
        val sponsoredBalance          = miner.balanceDetails(sponsorAddress)
        val sponsorFirstAssetBalance  = miner.assetBalance(sponsorAddress, firstSponsorAssetId).balance
        val sponsorSecondAssetBalance = miner.assetBalance(sponsorAddress, secondSponsorAssetId).balance
        val aliceFirstAssetBalance    = miner.assetBalance(aliceAddress, firstSponsorAssetId).balance
        val aliceSecondAssetBalance   = miner.assetBalance(aliceAddress, firstSponsorAssetId).balance
        val aliceWavesBalance         = miner.balanceDetails(aliceAddress)
        val bobWavesBalance           = miner.balanceDetails(bobAddress)
        val minerBalance              = miner.balanceDetails(miner.address)

        val transferTxCustomFeeAlice1 = miner.transfer(alice, bobAddress, 1.waves, LargeFee, None, Some(firstSponsorAssetId)).id
        val transferTxCustomFeeAlice2 = miner.transfer(alice, bobAddress, 1.waves, LargeFee, None, Some(secondSponsorAssetId)).id
        nodes.waitForHeightArise()
        nodes.waitForTransaction(transferTxCustomFeeAlice1)
        nodes.waitForTransaction(transferTxCustomFeeAlice2)
        val wavesFee = FeeValidation.FeeUnit * 2 * LargeFee / LargeFee
        nodes.waitForHeightArise()

        miner.assertBalances(sponsorAddress, sponsoredBalance.regular - wavesFee, sponsoredBalance.effective - wavesFee)
        miner.assertAssetBalance(sponsorAddress, firstSponsorAssetId, sponsorFirstAssetBalance + LargeFee)
        miner.assertAssetBalance(sponsorAddress, secondSponsorAssetId, sponsorSecondAssetBalance + LargeFee)
        miner.assertAssetBalance(aliceAddress, firstSponsorAssetId, aliceFirstAssetBalance - LargeFee)
        miner.assertAssetBalance(aliceAddress, secondSponsorAssetId, aliceSecondAssetBalance - LargeFee)

        miner.assertBalances(aliceAddress, aliceWavesBalance.regular - 2.waves, aliceWavesBalance.effective - 2.waves)
        miner.assertBalances(bobAddress, bobWavesBalance.regular + 2.waves, bobWavesBalance.effective + 2.waves)
        miner.assertBalances(miner.address, minerBalance.regular + wavesFee, minerBalance.effective + wavesFee)
      }

    }

    "issue asset make sponsor and burn and reissue" in {
      val sponsorBalance = miner.balanceDetails(sponsorAddress)
      val minerBalance   = miner.balanceDetails(miner.address)

      val firstSponsorAssetId2 =
        miner
          .issue(
            sponsor,
            "Another1",
            "Created by Sponsorship Suite",
            sponsorAssetTotal,
            decimals = 2,
            fee = issueFee,
            waitForTx = true
          )
          .id
      val secondSponsorAssetId2 =
        miner
          .issue(
            sponsor,
            "Another2",
            "Created by Sponsorship Suite",
            sponsorAssetTotal,
            decimals = 2,
            reissuable = true,
            fee = issueFee,
            waitForTx = true
          )
          .id
      val sponsorTxId1 = miner.sponsorAsset(sponsor, firstSponsorAssetId2, baseFee = Token, fee = sponsorReducedFee, version = TxVersion.V1).id
      val sponsorTxId2 = miner.sponsorAsset(sponsor, secondSponsorAssetId2, baseFee = Token, fee = sponsorReducedFee, version = TxVersion.V2).id
      miner.transfer(sponsor, aliceAddress, sponsorAssetTotal / 2, minFee, Some(firstSponsorAssetId2), None, waitForTx = true).id
      miner.transfer(sponsor, aliceAddress, sponsorAssetTotal / 2, minFee, Some(secondSponsorAssetId2), None, waitForTx = true).id
      nodes.waitForHeightAriseAndTxPresent(sponsorTxId1)
      nodes.waitForTransaction(sponsorTxId2)

      miner.burn(sponsor, firstSponsorAssetId2, sponsorAssetTotal / 2, burnFee, waitForTx = true).id
      miner.burn(sponsor, secondSponsorAssetId2, sponsorAssetTotal / 2, burnFee, waitForTx = true).id

      for (sponsorAssetId2 <- Seq(firstSponsorAssetId2, secondSponsorAssetId2)) {
        val assetInfo = miner.assetsDetails(sponsorAssetId2)
        assetInfo.minSponsoredAssetFee shouldBe Some(Token)
        assetInfo.quantity shouldBe sponsorAssetTotal / 2
      }

      miner.reissue(sponsor, firstSponsorAssetId2, sponsorAssetTotal, reissuable = true, issueFee, waitForTx = true).id
      miner.reissue(sponsor, secondSponsorAssetId2, sponsorAssetTotal, reissuable = true, issueFee, waitForTx = true).id

      for (sponsorAssetId2 <- Seq(firstSponsorAssetId2, secondSponsorAssetId2)) {
        val assetInfoAfterReissue = miner.assetsDetails(sponsorAssetId2)
        assetInfoAfterReissue.minSponsoredAssetFee shouldBe Some(Token)
        assetInfoAfterReissue.quantity shouldBe sponsorAssetTotal / 2 + sponsorAssetTotal
        assetInfoAfterReissue.reissuable shouldBe true
      }

      val aliceTransferWaves1 = miner.transfer(alice, bobAddress, transferAmount, SmallFee, None, Some(firstSponsorAssetId2), waitForTx = true).id
      val aliceTransferWaves2 = miner.transfer(alice, bobAddress, transferAmount, SmallFee, None, Some(secondSponsorAssetId2), waitForTx = true).id
      nodes.waitForHeightAriseAndTxPresent(aliceTransferWaves1)
      nodes.waitForHeightAriseAndTxPresent(aliceTransferWaves2)

      val totalWavesFee = FeeValidation.FeeUnit * 2 * SmallFee / Token + 2 * issueFee + 2 * sponsorReducedFee + 2 * burnFee + 2 * minFee + 2 * issueFee
      miner.assertBalances(miner.address, minerBalance.regular + totalWavesFee, minerBalance.effective + totalWavesFee)
      miner.assertBalances(sponsorAddress, sponsorBalance.regular - totalWavesFee, sponsorBalance.effective - totalWavesFee)
      miner.assertAssetBalance(sponsorAddress, firstSponsorAssetId2, SmallFee + sponsorAssetTotal)
      miner.assertAssetBalance(sponsorAddress, secondSponsorAssetId2, SmallFee + sponsorAssetTotal)
    }

    "miner is sponsor" in {
      val minerBalance = miner.balanceDetails(miner.address)
      val firstMinersAsset =
        miner
          .issue(
            miner.keyPair,
            "MinersAsset1",
            "Created by Sponsorship Suite",
            sponsorAssetTotal,
            decimals = 8,
            fee = issueFee,
            waitForTx = true
          )
          .id
      val secondMinersAsset =
        miner
          .issue(
            miner.keyPair,
            "MinersAsset2",
            "Created by Sponsorship Suite",
            sponsorAssetTotal,
            decimals = 8,
            reissuable = true,
            fee = issueFee,
            waitForTx = true
          )
          .id
      val firstSponsorshipTxId =
        miner.sponsorAsset(miner.keyPair, firstMinersAsset, baseFee = Token, fee = sponsorReducedFee, version = TxVersion.V1).id
      val secondSponsorshipTxId =
        miner.sponsorAsset(miner.keyPair, secondMinersAsset, baseFee = Token, fee = sponsorReducedFee, version = TxVersion.V2).id
      nodes.waitForHeightAriseAndTxPresent(firstSponsorshipTxId)
      nodes.waitForTransaction(secondSponsorshipTxId)
      val minerFirstTransferTxId =
        miner.transfer(miner.keyPair, aliceAddress, sponsorAssetTotal / 2, SmallFee, Some(firstMinersAsset), Some(firstMinersAsset)).id
      val minerSecondTransferTxId =
        miner.transfer(miner.keyPair, aliceAddress, sponsorAssetTotal / 2, SmallFee, Some(secondMinersAsset), Some(secondMinersAsset)).id
      nodes.waitForHeightAriseAndTxPresent(minerFirstTransferTxId)
      nodes.waitForHeightAriseAndTxPresent(minerSecondTransferTxId)

      miner.assertBalances(miner.address, minerBalance.regular)
      val aliceFirstTransferWavesId  = miner.transfer(alice, bobAddress, transferAmount, SmallFee, None, Some(firstMinersAsset)).id
      val aliceSecondTransferWavesId = miner.transfer(alice, bobAddress, transferAmount, SmallFee, None, Some(secondMinersAsset)).id
      nodes.waitForHeightAriseAndTxPresent(aliceFirstTransferWavesId)
      nodes.waitForHeightAriseAndTxPresent(aliceSecondTransferWavesId)

      miner.assertBalances(miner.address, minerBalance.regular)
      miner.assertAssetBalance(miner.address, firstMinersAsset, sponsorAssetTotal / 2 + SmallFee)
      miner.assertAssetBalance(miner.address, secondMinersAsset, sponsorAssetTotal / 2 + SmallFee)
    }

    "tx is declined if sponsor has not enough effective balance to pay fee" in {
      val sponsorEffectiveBalance = miner.balanceDetails(sponsorAddress).effective
      miner.lease(sponsor, bobAddress, sponsorEffectiveBalance - leasingFee, leasingFee, waitForTx = true).id
      assertBadRequestAndMessage(
        miner.transfer(alice, bobAddress, 10 * Token, LargeFee, Some(firstSponsorAssetId), Some(firstSponsorAssetId)),
        "unavailable funds"
      )
      assertBadRequestAndMessage(
        miner.transfer(alice, bobAddress, 10 * Token, LargeFee, Some(secondSponsorAssetId), Some(secondSponsorAssetId)),
        "unavailable funds"
      )
    }
  }
}
