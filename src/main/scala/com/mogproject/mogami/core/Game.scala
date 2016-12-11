package com.mogproject.mogami.core

import scala.annotation.tailrec
import com.mogproject.mogami.core.io.{CsaFactory, CsaLike, SfenFactory, SfenLike}

/**
  * Game
  */
case class Game(initialState: State = State.HIRATE,
                moves: Seq[ExtendedMove] = Seq.empty,
                gameInfo: GameInfo = GameInfo(),
                movesOffset: Int = 0
               ) extends CsaLike with SfenLike {

  require(history.length == moves.length + 1, "all moves must be valid")

  import com.mogproject.mogami.core.Game.GameStatus._

  /** history of states */
  lazy val history: Seq[State] = moves.scanLeft(Some(initialState): Option[State])((s, m) => s.flatMap(_.makeMove(m))).flatten

  lazy val hashCodes: Seq[Int] = history.map(_.hashCode())

  lazy val status: GameStatus = if (currentState.isMated) Mated else Playing

  /**
    * Get the latest state.
    */
  def currentState: State = history.last

  def makeMove(move: ExtendedMove): Option[Game] =
    Option(currentState.isValidMove(move)).collect { case true => this.copy(moves = moves :+ move) }

  def makeMove(move: Move): Option[Game] = ExtendedMove.fromMove(move, currentState).flatMap(makeMove)

  override def toCsaString: String =
    (gameInfo :: initialState :: moves.toList) map (_.toCsaString) filter (!_.isEmpty) mkString "\n"

  override def toSfenString: String = (initialState.toSfenString :: movesOffset.toString :: moves.map(_.toSfenString).toList).mkString(" ")

  /**
    * Check if the latest move is the repetition.
    *
    * @return true if the latest move is the repetition
    */
  def isRepetition: Boolean = hashCodes.count(_ == currentState.hashCode()) >= 4
}

object Game extends CsaFactory[Game] with SfenFactory[Game] {
  override def parseCsaString(s: String): Option[Game] = {
    def isStateText(t: String) = t.startsWith("P") || t == "+" || t == "-"

    for {
      xs <- Some(s.split('\n').toList)
      (a, ys) = xs.span(!isStateText(_))
      (b, c) = ys.span(isStateText)
      gi <- GameInfo.parseCsaString(a)
      st <- State.parseCsaString(b)
      // todo: parse time format (e.g. +7776FU,T12)
      moves = c.flatMap(s => Move.parseCsaString(s.take(7))) if moves.length == c.length
      game <- moves.foldLeft(Some(Game(st, Seq.empty, gi)): Option[Game])((g, m) => g.flatMap(_.makeMove(m)))
    } yield game
  }

  override def parseSfenString(s: String): Option[Game] = ???

  object GameStatus extends Enumeration {
    type GameStatus = Value
    val Playing, Mated = Value
  }

}


/**
  * Game information
  */
case class GameInfo(tags: Map[Symbol, String] = Map()) extends CsaLike {

  require(validateTagKeys)

  def validateTagKeys: Boolean = tags.keys forall { k => GameInfo.keys.map(_._1).contains(k) }

  def updated(key: Symbol, value: String): GameInfo = GameInfo(tags.updated(key, value))

  def toCsaString: String = {
    GameInfo.keys.toList.flatMap {
      case (k, c) if tags.contains(k) => List(c + tags(k))
      case _ => Nil
    } mkString "\n"
  }
}

object GameInfo extends CsaFactory[GameInfo] {
  def parseCsaString(s: String): Option[GameInfo] = {
    @tailrec
    def f(ss: List[String], sofar: Option[GameInfo]): Option[GameInfo] = (ss, sofar) match {
      case (x :: xs, Some(gt)) =>
        keys.filter { k => x.startsWith(k._2) } match {
          case (k, c) :: _ => f(xs, Some(gt.updated(k, x.substring(c.length))))
          case _ => None
        }
      case _ => sofar // (_, None) => None; (Nil, _) => sofar
    }

    f(if (s.isEmpty) List() else s.split('\n').toList, Some(GameInfo()))
  }

  /** pairs of a symbol name and its csa-formatted string */
  val keys: Seq[(Symbol, String)] = Seq(
    ('formatVersion, "V"),
    ('blackName, "N+"),
    ('whiteName, "N-"),
    ('event, "$EVENT:"),
    ('site, "$SITE:"),
    ('startTime, "$START_TIME:"),
    ('endTime, "$END_TIME:"),
    ('timeLimit, "$TIME_LIMIT:"),
    ('opening, "$OPENING:")
  )
}