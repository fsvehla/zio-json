package zio.json

import scala.annotation._
import scala.collection.mutable
import scala.collection.immutable

// convenient `.toJson` syntax
object syntax {
  implicit final class EncoderOps[A](private val a: A) extends AnyVal {
    def toJson(implicit A: Encoder[A]): String = A.toJson(a, None)

    // Jon Pretty's better looking brother, but a bit slower
    def toJsonPretty(implicit A: Encoder[A]): String = A.toJson(a, Some(0))
  }
}

trait Encoder[A] { self =>
  def toJson(a: A, indent: Option[Int]): String = {
    val writer = new internal.FastStringWriter(64)
    unsafeEncode(a, indent, writer)
    writer.toString
  }

  // scalaz-deriving style Contravariant combinators
  final def narrow[B <: A]: Encoder[B] = self.asInstanceOf[Encoder[B]]
  final def contramap[B](f: B => A): Encoder[B] = new Encoder[B] {
    override def unsafeEncode(b: B, indent: Option[Int], out: java.io.Writer): Unit =
      self.unsafeEncode(f(b), indent, out)
    override def isNothing(b: B): Boolean = self.isNothing(f(b))
  }
  final def xmap[B](f: A => B, g: B => A): Encoder[B] = contramap(g)

  def unsafeEncode(a: A, indent: Option[Int], out: java.io.Writer): Unit

  // override and return `true` when this value may be skipped from JSON Objects
  def isNothing(a: A): Boolean = false
}

object Encoder extends GeneratedTupleEncoders with EncoderLowPriority1 {
  def apply[A](implicit a: Encoder[A]): Encoder[A] = a

  implicit val string: Encoder[String] = new Encoder[String] {
    override def unsafeEncode(a: String, indent: Option[Int], out: java.io.Writer): Unit = {
      out.write('"')
      var i   = 0
      val len = a.length
      while (i < len) {
        (a.charAt(i): @switch) match {
          case '"'  => out.write("\\\"")
          case '\\' => out.write("\\\\")
          case '\b' => out.write("\\b")
          case '\f' => out.write("\\f")
          case '\n' => out.write("\\n")
          case '\r' => out.write("\\r")
          case '\t' => out.write("\\t")
          case c =>
            if (c < ' ') out.write("\\u%04x".format(c.toInt))
            else out.write(c)
        }
        i += 1
      }
      out.write('"')
    }

  }

  private[this] def explicit[A](f: A => String): Encoder[A] = new Encoder[A] {
    def unsafeEncode(a: A, indent: Option[Int], out: java.io.Writer): Unit = out.write(f(a))
  }
  implicit val boolean: Encoder[Boolean] = explicit(_.toString)
  implicit val char: Encoder[Char]       = string.contramap(_.toString)
  implicit val symbol: Encoder[Symbol]   = string.contramap(_.name)

  implicit val byte: Encoder[Byte]                       = explicit(_.toString)
  implicit val short: Encoder[Short]                     = explicit(_.toString)
  implicit val int: Encoder[Int]                         = explicit(_.toString)
  implicit val long: Encoder[Long]                       = explicit(_.toString)
  implicit val biginteger: Encoder[java.math.BigInteger] = explicit(_.toString)
  implicit val double: Encoder[Double] = explicit { n =>
    if (n.isNaN || n.isInfinite) s""""$n""""
    else n.toString
  }
  implicit val float: Encoder[Float]                     = double.contramap(_.toDouble)
  implicit val bigdecimal: Encoder[java.math.BigDecimal] = explicit(_.toString)

  implicit def option[A](implicit A: Encoder[A]): Encoder[Option[A]] = new Encoder[Option[A]] {
    def unsafeEncode(oa: Option[A], indent: Option[Int], out: java.io.Writer): Unit = oa match {
      case None    => out.write("null")
      case Some(a) => A.unsafeEncode(a, indent, out)
    }
    override def isNothing(a: Option[A]): Boolean = a.isEmpty
  }

  def bump(indent: Option[Int]): Option[Int] = indent match {
    case None    => None
    case Some(i) => Some(i + 1)
  }
  def pad(indent: Option[Int], out: java.io.Writer): Unit =
    indent.foreach(i => out.write("\n" + (" " * 2 * i)))

  implicit def either[A, B](implicit A: Encoder[A], B: Encoder[B]): Encoder[Either[A, B]] = new Encoder[Either[A, B]] {
    def unsafeEncode(eab: Either[A, B], indent: Option[Int], out: java.io.Writer): Unit = {
      out.write("{")
      val indent_ = bump(indent)
      pad(indent_, out)
      eab match {
        case Left(a) =>
          out.write("\"Left\"")
          if (indent.isEmpty) out.write(":")
          else out.write(" : ")
          A.unsafeEncode(a, indent_, out)
        case Right(b) =>
          out.write("\"Right\"")
          if (indent.isEmpty) out.write(":")
          else out.write(" : ")
          B.unsafeEncode(b, indent_, out)
      }
      pad(indent, out)
      out.write("}")
    }
  }
}

private[json] trait EncoderLowPriority1 {
  this: Encoder.type =>

  implicit def list[A: Encoder]: Encoder[List[A]]     = seq[A].narrow
  implicit def vector[A: Encoder]: Encoder[Vector[A]] = seq[A].narrow
  implicit def seq[A](implicit A: Encoder[A]): Encoder[Seq[A]] = new Encoder[Seq[A]] {
    def unsafeEncode(as: Seq[A], indent: Option[Int], out: java.io.Writer): Unit = {
      out.write("[")
      var first = true
      as.foreach { a =>
        if (first) first = false
        else if (indent.isEmpty) out.write(",")
        else out.write(", ")
        A.unsafeEncode(a, indent, out)
      }
      out.write("]")
    }
  }

  // not implicit because this overlaps with encoders for lists of tuples
  def keylist[K, A](
    implicit
    K: FieldEncoder[K],
    A: Encoder[A]
  ): Encoder[List[(K, A)]] = new Encoder[List[(K, A)]] {
    def unsafeEncode(kvs: List[(K, A)], indent: Option[Int], out: java.io.Writer): Unit = {
      if (kvs.isEmpty) return out.write("{}")

      out.write("{")
      val indent_ = bump(indent)
      pad(indent_, out)
      var first = true
      kvs.foreach {
        case (k, a) =>
          if (!A.isNothing(a)) {
            if (first)
              first = false
            else if (indent.isEmpty)
              out.write(",")
            else {
              out.write(",")
              pad(indent_, out)
            }

            string.unsafeEncode(K.unsafeEncodeField(k), indent_, out)
            if (indent.isEmpty) out.write(":")
            else out.write(" : ")
            A.unsafeEncode(a, indent_, out)
          }
      }
      pad(indent, out)
      out.write("}")
    }
  }

  // TODO these could be optimised...
  implicit def sortedmap[K: FieldEncoder, V: Encoder]: Encoder[collection.SortedMap[K, V]] =
    keylist[K, V].contramap(_.toList)
  implicit def map[K: FieldEncoder, V: Encoder]: Encoder[Map[K, V]] =
    keylist[K, V].contramap(_.toList)
  implicit def hashmap[K: FieldEncoder, V: Encoder]: Encoder[immutable.HashMap[K, V]] =
    keylist[K, V].contramap(_.toList)
  implicit def set[A: Encoder]: Encoder[Set[A]] =
    list[A].contramap(_.toList)
  implicit def hashset[A: Encoder]: Encoder[immutable.HashSet[A]] =
    list[A].contramap(_.toList)
  implicit def sortedset[A: Ordering: Encoder]: Encoder[immutable.SortedSet[A]] =
    list[A].contramap(_.toList)

}

/** When encoding a JSON Object, we only allow keys that implement this interface. */
trait FieldEncoder[A] { self =>

  final def narrow[B <: A]: FieldEncoder[B] = self.asInstanceOf[FieldEncoder[B]]
  final def contramap[B](f: B => A): FieldEncoder[B] = new FieldEncoder[B] {
    override def unsafeEncodeField(in: B): String = self.unsafeEncodeField(f(in))
  }
  final def xmap[B](f: A => B, g: B => A): FieldEncoder[B] = contramap(g)

  def unsafeEncodeField(in: A): String
}
object FieldEncoder {
  implicit val string: FieldEncoder[String] = new FieldEncoder[String] {
    def unsafeEncodeField(in: String): String = in
  }
}
