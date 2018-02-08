package scorex.transaction.smart

import com.wavesplatform.lang.Evaluator
import com.wavesplatform.lang.Evaluator.Context
import com.wavesplatform.lang.Terms._
import com.wavesplatform.state2.reader.SnapshotStateReader
import monix.eval.Coeval
import scodec.bits.ByteVector
import scorex.crypto.EllipticCurveImpl
import scorex.transaction.ValidationError.{GenericError, TransactionNotAllowedByScript}
import scorex.transaction._

object Verifier {

  def apply(s: SnapshotStateReader, currentBlockHeight: Int)(tx: Transaction): Either[ValidationError, Transaction] = tx match {
    case _: GenesisTransaction => Right(tx)
    case pt: ProvenTransaction =>
      (pt, s.accountScript(pt.sender)) match {
        case (_, Some(script))              => verify(script, currentBlockHeight, pt)
        case (stx: SignedTransaction, None) => stx.signaturesValid()
        case _                              => verifyAsEllipticCurveSignature(pt)
      }
  }
  def verify[T <: ProvenTransaction](script: Script, height: Int, transaction: T): Either[ValidationError, T] = {

    val context = Context(Map("Transaction" -> transactionType),
                          Map(
                            "H"  -> (INT, height),
                            "TX" -> (TYPEREF("Transaction"), transactionObject(transaction))
                          ))
    Evaluator.apply[Boolean](context, script.script) match {
      case Left(execError) => Left(GenericError(s"Script execution error: $execError"))
      case Right(false)    => Left(TransactionNotAllowedByScript(transaction))
      case Right(true)     => Right(transaction)
    }
  }
  def verifyAsEllipticCurveSignature[T <: ProvenTransaction](pt: T): Either[ValidationError, T] =
    Either.cond(
      EllipticCurveImpl.verify(pt.proofs.proofs(0).arr, pt.bodyBytes(), pt.sender.publicKey),
      pt,
      GenericError(s"Script doesn't exist and proof doesn't validate as signature for $pt")
    )

  val transactionType = CUSTOMTYPE(
    "Transaction",
    List("TYPE"      -> INT,
         "ID"        -> BYTEVECTOR,
         "BODYBYTES" -> BYTEVECTOR,
         "SENDERPK"  -> BYTEVECTOR,
         "PROOFA" -> BYTEVECTOR,
         "PROOFB" -> BYTEVECTOR,
         "PROOFC" -> BYTEVECTOR)
  )

  def thro: Nothing = throw new IllegalArgumentException("transactions is of another type")

  private def proofBinding(tx: Transaction, x: Int) =
    LazyVal(BYTEVECTOR)(tx match {
      case pt: ProvenTransaction =>
        val proof: ByteVector =
          if (x >= pt.proofs.proofs.size)
            ByteVector.empty
          else ByteVector(pt.proofs.proofs(x).arr)
        Coeval.evalOnce(proof)
      case _ => Coeval(thro)
    })

  private def transactionObject(tx: Transaction) =
    OBJECT(
      Map(
        "TYPE" -> LazyVal(INT)(Coeval.evalOnce(tx.transactionType.id)),
        "ID"   -> LazyVal(BYTEVECTOR)(tx.id.map(_.arr).map(ByteVector(_))),
        "BODYBYTES" -> LazyVal(BYTEVECTOR)(tx match {
          case pt: ProvenTransaction => pt.bodyBytes.map(ByteVector(_))
          case _                     => Coeval.evalOnce(thro)
        }),
        "SENDERPK" -> LazyVal(BYTEVECTOR)(tx match {
          case pt: Authorized => Coeval.evalOnce(ByteVector(pt.sender.publicKey))
          case _              => Coeval.evalOnce(thro)
        }),
        "PROOFA" -> proofBinding(tx, 0),
        "PROOFB" -> proofBinding(tx, 1),
        "PROOFC" -> proofBinding(tx, 2)
      ))

}