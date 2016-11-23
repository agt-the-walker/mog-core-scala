package com.mogproject.mogami.core

import com.mogproject.mogami.core.io.{CsaFactory, CsaLike, SfenFactory, SfenLike}

/**
  * Player
  */
sealed abstract class Player(val id: Int) extends CsaLike with SfenLike {
  def unary_! : Player = Player(id ^ 1)

  def doWhenWhite[A](a: => A)(f: A => A): A = if (id == 0) a else f(a)

  override def toCsaString: String = Player.csaTable(id)

  override def toSfenString: String = Player.sfenTable(id)
}

object Player extends CsaFactory[Player] with SfenFactory[Player] {
  override val csaTable: Seq[String] = Seq("+", "-")

  override val sfenTable: Seq[String] = Seq("b", "w")

  val constructor: Seq[Player] = Seq(BLACK, WHITE)

  def apply(id: Int): Player = {
    assert(0 <= id && id < 2)
    constructor(id)
  }

  case object BLACK extends Player(0)

  case object WHITE extends Player(1)

}
