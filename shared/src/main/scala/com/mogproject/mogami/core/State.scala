package com.mogproject.mogami.core

import com.mogproject.mogami._
import com.mogproject.mogami.core.io._
import com.mogproject.mogami.core.move.{MoveBuilderSfenBoard, MoveBuilderSfenHand}
import com.mogproject.mogami.util.Implicits._
import com.mogproject.mogami.util.MapUtil

import scala.collection.mutable
import scala.util.Try


/**
  * State class
  */
case class State(turn: Player = BLACK,
                 board: BoardType = Map.empty,
                 hand: HandType = State.EMPTY_HANDS,
                 lastMoveTo: Option[Square] = None
                ) extends CsaLike with SfenLike with KifLike {

  require(checkCapacity, "the number of pieces must be within the capacity")
  require(hand.keySet == State.EMPTY_HANDS.keySet, "hand pieces must be in-hand type")
  require(board.forall { case (s, p) => s.isLegalZone(p) }, "all board pieces must be placed in their legal zones")
  require(!getKing(!turn).exists(getAttackBB(turn).get), "player must not be able to attack the opponent's king")
  require(!isNifu, "two pawns cannot be in the same file")

  import com.mogproject.mogami.core.State.MoveFrom
  import com.mogproject.mogami.core.State.PromotionFlag.{CanPromote, CannotPromote, MustPromote, PromotionFlag}

  /**
    * Test if the board is Nifu.
    *
    * @return true if Nifu
    */
  private[this] def isNifu: Boolean = Player.constructor.exists { pl =>
    val files = board.withFilter(_._2 == Piece(pl, PAWN)).map(_._1.file).toSeq
    files.length != files.distinct.length
  }

  override def toCsaString: String = {
    val boardString = (1 to 9).map { rank =>
      (9 to 1 by -1).map { file => board.get(Square(file, rank)).map(_.toCsaString).getOrElse(" * ") }.mkString(s"P$rank", "", "")
    }.mkString("\n")

    val handString = Player.constructor.map { p =>
      s"P${p.toCsaString}" + Ptype.inHand.map { pt => s"00${pt.toCsaString}" * hand.getOrElse(Hand(p, pt), 0) }.mkString
    }.mkString("\n")

    Seq(boardString, handString, turn.toCsaString).mkString("\n")
  }

  override def toSfenString: String = {
    def stringifyNumber(n: Int, threshold: Int = 0): String = (n <= threshold).fold("", n.toString)

    val boardString = (1 to 9).map { rank =>
      val (ss, nn) = (9 to 1 by -1).map { file =>
        board.get(Square(file, rank))
      }.foldLeft(("", 0)) {
        case ((s, n), Some(p)) => (s + stringifyNumber(n) + p.toSfenString, 0)
        case ((s, n), None) => (s, n + 1)
      }
      ss + stringifyNumber(nn)
    }.mkString("/")

    val handString = hand.filter(_._2 != 0).toSeq.sorted.map { case (p, n) => stringifyNumber(n, 1) + p.toPiece.toSfenString }.mkString

    s"$boardString ${turn.toSfenString} ${handString.isEmpty.fold("-", handString)}"
  }

  private[this] def numberToJapanese(n: Int): String = n match {
    case _ if 1 <= n && n <= 18 =>
      Vector("", "十")(n / 10) + Vector("", "一", "二", "三", "四", "五", "六", "七", "八", "九")(n % 10)
    case _ => ""
  }

  override def toKifString: String = {
    val handString = Player.constructor.map(pl => {
      val xs = hand.toSeq.sorted.flatMap {
        case (p, n) if p.owner == pl && n != 0 =>
          Seq(p.ptype.toJapaneseSimpleName + (n >= 2).fold(numberToJapanese(n), "") + "　")
        case _ => Seq()
      }
      xs.isEmpty.fold("なし", xs.mkString(""))
    })

    // todo: 上手/下手
    (Seq(
      "後手の持駒：" + handString(1),
      "  ９ ８ ７ ６ ５ ４ ３ ２ １",
      "+---------------------------+",
      (1 to 9).map { rank =>
        (9 to 1 by -1).map {
          file => board.get(Square(file, rank)).map(_.toKifString).getOrElse(" ・")
        }.mkString("|", "", s"|${numberToJapanese(rank)}")
      }.mkString("\n"),
      "+---------------------------+",
      "先手の持駒：" + handString(0)
    ) ++ turn.isBlack.fold(Seq.empty, Seq("後手番"))).mkString("\n")
  }

  def updateBoardPiece(square: Square, piece: Piece): Option[State] = Try(copy(board = board.updated(square, piece))).toOption

  def updateHandPiece(piece: Piece, num: Int): Option[State] = Try(copy(hand = hand.updated(Hand(piece), num))).toOption

  /**
    * Occupancy bitboards
    */
  private[this] def aggregateSquares(boardMap: BoardType): BitBoard = boardMap.keys.view.map(BitBoard.ident).fold(BitBoard.empty)(_ | _)

  private[this] lazy val occupancyAll: BitBoard = aggregateSquares(board)

  private[this] lazy val occupancyByOwner: Map[Player, BitBoard] = board.groupBy(_._2.owner).mapValues(aggregateSquares)

  private[this] lazy val occupancyByPiece: Map[Piece, BitBoard] = board.groupBy(_._2).mapValues(aggregateSquares)

  def occupancy: BitBoard = occupancyAll

  def occupancy(player: Player): BitBoard = occupancyByOwner.getOrElse(player, BitBoard.empty)

  def occupancy(piece: Piece): BitBoard = occupancyByPiece.getOrElse(piece, BitBoard.empty)

  def getSquares(piece: Piece): Set[Square] = occupancy(piece).toSet

  def getKing(player: Player): Option[Square] = occupancy(Piece(player, KING)).toList.headOption

  lazy val turnsKing: Option[Square] = getKing(turn)

  def getRangedPieces(player: Player): Seq[(Square, Piece)] = board.filter { case (_, p) => p.owner == player && p.isRanged }.toSeq

  /**
    * Attack bitboards
    */
  lazy val attackBBOnBoard: Map[Player, Map[Square, BitBoard]] = {
    val m = (for ((sq, piece@Piece(owner, _)) <- board) yield {
      (owner, sq) -> Attack.get(piece, Some(sq), occupancy, occupancy(Piece(owner, PAWN)))
    }).filter(_._2.nonEmpty).groupBy(_._1._1).mapValues(_.map { case ((_, s), b) => s -> b })

    Map(BLACK -> Map.empty[Square, BitBoard], WHITE -> Map.empty[Square, BitBoard]) ++ m
  }

  lazy val attackBBInHand: Map[Hand, BitBoard] = for {
    (h, num) <- hand if h.owner == turn && num > 0
  } yield {
    h -> Attack.get(h.toPiece, None, occupancy, occupancy(Piece(turn, PAWN)))
  }

  def getAttackBB(player: Player): BitBoard = attackBBOnBoard(player).values.fold(BitBoard.empty)(_ | _)

  /**
    * Get the positions of pieces that are attacking the turn player's king
    *
    * @return set of squares
    */
  lazy val attackers: Set[Square] = turnsKing.map(k => attackBBOnBoard(!turn).filter(_._2.get(k)).keys.toSet).getOrElse(Set.empty)

  /**
    * Get the guard pieces, which protect the turn player's king from ranged attack.
    *
    * @return set of squares and guarding area + attacker bitboards
    */
  lazy val guards: Map[Square, BitBoard] = {
    for {
      (s, p) <- board if p.owner == !turn && p.isRanged
      k <- getKing(turn)
      bt = s.getBetweenBB(k) if Attack.getRangedAttack(p, s, BitBoard.empty).get(k)
      g = bt & occupancy if g.count == 1
    } yield {
      g.toList.head -> bt.set(s)
    }
  }

  /**
    * Check if the player is checked.
    */
  lazy val isChecked: Boolean = turnsKing.exists(getAttackBB(!turn).get)

  def getNonSuicidalMovesOnBoard: Map[Square, BitBoard] = for ((sq, bb) <- attackBBOnBoard(turn)) yield {
    if (board(sq).ptype == KING)
      sq -> (bb & ~getAttackBB(!turn))
    else if (guards.keySet.contains(sq))
      sq -> (bb & guards(sq))
    else
      sq -> bb
  }

  def getEscapeMoves: Map[MoveFrom, BitBoard] = {
    require(turnsKing.isDefined)

    // king's move
    val king = turnsKing.get
    val occExceptKing = occupancyAll.reset(king)
    val attackerPotentialBB: BitBoard = attackers.map(sq => Attack.get(board(sq), Some(sq), occExceptKing, BitBoard.empty)).fold(BitBoard.empty)(_ | _)
    val kingEscape = Map(king -> (attackBBOnBoard(turn)(king) & ~(getAttackBB(!turn) | occupancy(turn) | attackerPotentialBB)))

    // move a piece between king and the attacker or capture the attacker (except king's move)
    val attacker = if (attackers.size == 1) attackers.headOption else None
    val between = attacker.map(king.getBetweenBB)
    val betweenAndAttacker = attacker.map(atk => between.get.set(atk))

    val moveBetween = for {
      (sq, bb) <- getNonSuicidalMovesOnBoard if sq != king
      bt <- betweenAndAttacker
    } yield sq -> (bb & bt)

    // drop a piece between king and the attacker
    val dropBetween = for ((sq, bb) <- attackBBInHand; bt <- between) yield sq -> (bb & bt)

    (kingEscape ++ moveBetween).map { case (k, v) => Left(k) -> v } ++ dropBetween.map { case (k, v) => Right(k) -> v }
  }

  /**
    * All legal moves in the bitboard description
    *
    * @return map of the square from MoveFrom and attack bitboard
    */
  lazy val legalMovesBB: Map[MoveFrom, BitBoard] = {
    val m = if (isChecked)
      getEscapeMoves
    else
      getNonSuicidalMovesOnBoard.map { case (k, v) => Left(k) -> v } ++ attackBBInHand.map { case (k, v) => Right(k) -> v }
    m.mapValues(_ & ~occupancy(turn)).filter(_._2.nonEmpty)
  }

  /**
    * Get all legal moves.
    *
    * @note This method can be relatively expensive.
    * @return list of legal moves
    */
  def legalMoves: Seq[move.Move] = (
    for {
      (from, bb) <- legalMovesBB
      to <- bb.toList
      promote <- getPromotionList(from, to)
      mv <- from.fold(MoveBuilderSfenBoard(_, to, promote), p => MoveBuilderSfenHand(p.ptype, to)).toMove(this)
    } yield mv).toSeq

  /** *
    * Check if the state is mated.
    *
    * @return true if mated
    */
  def isMated: Boolean = legalMovesBB.isEmpty

  /**
    * Make one move.
    *
    * @param move move to make
    * @return new state
    */
  def makeMove(move: Move): Option[State] = isValidMove(move).option {
    val releaseBoard: BoardType => BoardType = move.from.when(sq => b => b - sq)
    val releaseHand: HandType => HandType = move.isDrop.when(MapUtil.decrementMap(_, Hand(move.newPiece)))
    val obtainHand: HandType => HandType = move.capturedPiece.when(p => h => MapUtil.incrementMap(h, Hand(!p.demoted)))
    State(!turn, releaseBoard(board) + (move.to -> move.newPiece), (releaseHand andThen obtainHand) (hand), Some(move.to))
  }

  lazy val getUsedPtypeCount: Map[Ptype, Int] = {
    val a = board.values.map(_.ptype.demoted).foldLeft(Map.empty[Ptype, Int]) { case (m, pt) => MapUtil.incrementMap(m, pt) }
    MapUtil.mergeMaps(a, hand.map { case (k, v) => k.ptype -> v })(_ + _, 0)
  }

  lazy val getUnusedPtypeCount: Map[Ptype, Int] = MapUtil.mergeMaps(State.capacity, getUsedPtypeCount)(_ - _, 0)

  lazy val checkCapacity: Boolean = {
    occupancy(Piece(BLACK, KING)).count <= 1 && occupancy(Piece(WHITE, KING)).count <= 1 && getUnusedPtypeCount.values.forall(_ >= 0)
  }

  def canAttack(from: Square, to: Square): Boolean = canAttack(Left(from), to)

  def canAttack(from: MoveFrom, to: Square): Boolean = legalMovesBB.get(from).exists(_.get(to))

  /**
    * @note This method does not check the capability of the piece
    */
  def getPromotionFlag(from: MoveFrom, to: Square): Option[PromotionFlag] = {
    from match {
      case Left(fr) =>
        for (p <- board.get(fr) if p.owner == turn) yield {
          (p.ptype.canPromote, fr.isPromotionZone(turn) || to.isPromotionZone(turn), to.isLegalZone(p)) match {
            case (true, true, true) => CanPromote
            case (true, true, false) => MustPromote
            case _ => CannotPromote
          }
        }
      case Right(_) => Some(CannotPromote)
    }
  }

  private[this] def getPromotionList(from: MoveFrom, to: Square): List[Boolean] = getPromotionFlag(from, to) match {
    case Some(CanPromote) => List(false, true)
    case Some(MustPromote) => List(true)
    case Some(CannotPromote) => List(false)
    case None => List()
  }

  /**
    * Check if the move is legal.
    *
    * @param move move to test
    * @return true if the move is legal
    */
  def isValidMove(move: Move): Boolean = {
    val mf = move.moveFrom
    canAttack(mf, move.to) && getPromotionList(mf, move.to).contains(move.promote)
  }

  /**
    * Check if the in-hand piece is non-empty.
    */
  def hasHand(h: Hand): Boolean = hand.get(h).exists(_ > 0)
}

object State extends CsaStateReader with SfenStateReader with KifStateReader {

  type BoardType = Map[Square, Piece]
  type HandType = Map[Hand, Int]

  // board or hand
  type MoveFrom = Either[Square, Hand]

  object PromotionFlag {

    sealed trait PromotionFlag

    case object CannotPromote extends PromotionFlag

    case object CanPromote extends PromotionFlag

    case object MustPromote extends PromotionFlag

  }

  val EMPTY_HANDS: HandType = (for (t <- Player.constructor; pt <- Ptype.inHand) yield Hand(t, pt) -> 0).toMap

  val empty = State(BLACK, Map.empty, EMPTY_HANDS)
  lazy val capacity: Map[Ptype, Int] = Map(PAWN -> 18, LANCE -> 4, KNIGHT -> 4, SILVER -> 4, GOLD -> 4, BISHOP -> 2, ROOK -> 2, KING -> 2)

  // constant states
  lazy val HIRATE = StateConstant.HIRATE
  lazy val MATING_BLACK = StateConstant.MATING_BLACK
  lazy val MATING_WHITE = StateConstant.MATING_WHITE
  lazy val HANDICAP_LANCE = StateConstant.HANDICAP_LANCE
  lazy val HANDICAP_BISHOP = StateConstant.HANDICAP_BISHOP
  lazy val HANDICAP_ROOK = StateConstant.HANDICAP_ROOK
  lazy val HANDICAP_ROOK_LANCE = StateConstant.HANDICAP_ROOK_LANCE
  lazy val HANDICAP_2_PIECE = StateConstant.HANDICAP_2_PIECE
  lazy val HANDICAP_3_PIECE = StateConstant.HANDICAP_3_PIECE
  lazy val HANDICAP_4_PIECE = StateConstant.HANDICAP_4_PIECE
  lazy val HANDICAP_5_PIECE = StateConstant.HANDICAP_5_PIECE
  lazy val HANDICAP_6_PIECE = StateConstant.HANDICAP_6_PIECE
  lazy val HANDICAP_8_PIECE = StateConstant.HANDICAP_8_PIECE
  lazy val HANDICAP_10_PIECE = StateConstant.HANDICAP_10_PIECE
  lazy val HANDICAP_THREE_PAWNS = StateConstant.HANDICAP_THREE_PAWNS
  lazy val HANDICAP_NAKED_KING = StateConstant.HANDICAP_NAKED_KING

}