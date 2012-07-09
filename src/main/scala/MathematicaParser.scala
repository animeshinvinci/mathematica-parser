package org.sympy.parsing.mathematica

import java.io.File
import scala.io.Source

import scala.util.parsing.combinator._
import scala.util.parsing.input.{Positional,Position}

sealed trait Expr {
    def toPrettyForm: String
}

case class Sym(name: String) extends Expr {
    def toPrettyForm = name
}

case class Num(value: String) extends Expr {
    def toPrettyForm = value
}

case class Str(value: String) extends Expr {
    def toPrettyForm = "\"%s\"".format(value.replace("\"", "\\\\\""))
}

sealed trait EvalLike extends Expr {
    val head: String
    val args: Seq[Expr]

    def toPrettyForm = {
        val args = this.args.map(_.toPrettyForm).mkString(", ")
        s"$head[$args]"
    }
}

case class Eval(head: String, args: Expr*) extends EvalLike

sealed trait Builtin extends EvalLike with scala.Product {
    val head = productPrefix
}

sealed trait Singleton extends Builtin {
    final val args: Seq[Expr] = Nil

    override def toPrettyForm = head
}

object Builtins {
    case class Plus(args: Expr*) extends Builtin
    case class Subtract(args: Expr*) extends Builtin
    case class Times(args: Expr*) extends Builtin
    case class Divide(args: Expr*) extends Builtin
    case class Power(args: Expr*) extends Builtin
    case class List(args: Expr*) extends Builtin
    case class Or(args: Expr*) extends Builtin
    case class And(args: Expr*) extends Builtin
    case class Not(args: Expr*) extends Builtin
    case class Equal(args: Expr*) extends Builtin
    case class Unequal(args: Expr*) extends Builtin
    case class Less(args: Expr*) extends Builtin
    case class Greater(args: Expr*) extends Builtin
    case class Span(args: Expr*) extends Builtin
    case class Part(args: Expr*) extends Builtin
    case class Exp(args: Expr*) extends Builtin
}

object Singletons {
    case object All extends Singleton
    case object True extends Singleton
    case object False extends Singleton
}

object Implicits {
    implicit def intToNum(value: Int): Expr = Num(value.toString)
    implicit def doubleToNum(value: Double): Expr = Num(value.toString)
    implicit def stringToStr(value: String): Expr = Str(value)
    implicit def symbolToSym(value: Symbol): Expr = Sym(value.name)
    implicit def booleanToBoolean(value: Boolean): Expr =
        if (value) Singletons.True else Singletons.False
}

class MathematicaParser extends RegexParsers with PackratParsers {
    protected override val whiteSpace = """(\s|(?m)\(\*(\*(?!/)|[^*])*\*\))+""".r

    import Implicits._

    lazy val ident: PackratParser[String] = regex("""[a-zA-Z][a-zA-Z0-9]*""".r)

    lazy val symbol: PackratParser[Sym] = ident ^^ {
        case name => Sym(name)
    }

    lazy val number: PackratParser[Num] = """(\d+\.\d*|\d*\.\d+|\d+)([eE][+-]?\d+)?""".r ^^ {
        case value => Num(value)
    }

    lazy val str: PackratParser[Str] = ("\"" + """(\\.|[^"])*""" + "\"").r ^^ {
        case value => Str(value.stripPrefix("\"").stripSuffix("\"").replace("\\\\\"", "\""))
    }

    lazy val apply: PackratParser[Expr] = ident ~ ("[" ~> repsep(expr, ",") <~ "]") ^^ {
        // TODO: this has to be automated (e.g. with reflection)
        case "Exp" ~ args => Builtins.Exp(args: _*)
        case head  ~ args => Eval(head, args: _*)
    }

    lazy val list: PackratParser[Expr] = "{" ~ repsep(expr, ",") ~ "}" ^^ {
        case "{" ~ elems ~ "}" => Builtins.List(elems: _*)
    }

    lazy val or: PackratParser[Expr] = (or | orExpr) ~ "||" ~ orExpr ^^ {
        case lhs ~ _ ~ rhs => Builtins.Or(lhs, rhs)
    }
    lazy val orExpr: PackratParser[Expr] = and | not | eq | cmp | tightest

    lazy val and: PackratParser[Expr] = (and | andExpr) ~ "&&" ~ andExpr ^^ {
        case lhs ~ _ ~ rhs => Builtins.And(lhs, rhs)
    }
    lazy val andExpr: PackratParser[Expr] = not | eq | cmp | tightest

    lazy val not: PackratParser[Expr] = "!" ~ (not | notExpr) ^^ {
        case _ ~ expr => Builtins.Not(expr)
    }
    lazy val notExpr: PackratParser[Expr] = eq | cmp | tightest

    lazy val eq: PackratParser[Expr] =  (eq | eqExpr) ~ ("==" | "!=") ~ eqExpr ^^ {
        case lhs ~ "==" ~ rhs => Builtins.Equal(lhs, rhs)
        case lhs ~ "!=" ~ rhs => Builtins.Unequal(lhs, rhs)
    }
    lazy val eqExpr: PackratParser[Expr] = cmp | add | mul | exp | neg | tightest

    lazy val cmp: PackratParser[Expr] = (cmp | cmpExpr) ~ ("<" | ">") ~ cmpExpr ^^ {
        case lhs ~ "<" ~ rhs => Builtins.Less(lhs, rhs)
        case lhs ~ ">" ~ rhs => Builtins.Greater(lhs, rhs)
    }
    lazy val cmpExpr: PackratParser[Expr] = add | mul | exp | neg | tightest

    lazy val add: PackratParser[Expr] = (add | addExpr) ~ ("+" | "-") ~ addExpr ^^ {
        case lhs ~ "+" ~ rhs => Builtins.Plus(lhs, rhs)
        case lhs ~ "-" ~ rhs => Builtins.Subtract(lhs, rhs)
    }
    lazy val addExpr: PackratParser[Expr] = mul | exp | neg | tightest

    lazy val mul: PackratParser[Expr] = mulImplied | mulExplicit
    lazy val mulImplied: PackratParser[Expr] = (mul | mulExpr) ~ mulExpr ^^ {
        case lhs ~ rhs  => Builtins.Times(lhs, rhs)
    }
    lazy val mulExplicit: PackratParser[Expr] = (mul | mulExpr) ~ ("*" | "/") ~ mulExpr ^^ {
        case lhs ~ "*" ~ rhs => Builtins.Times(lhs, rhs)
        case lhs ~ "/" ~ rhs => Builtins.Divide(lhs, rhs)
    }
    lazy val mulExpr: PackratParser[Expr] = exp | neg | tightest

    lazy val exp: PackratParser[Expr] = tightest ~ "^" ~ (exp | expExpr) ^^ {
        case lhs ~ _ ~ rhs => Builtins.Power(lhs, rhs)
    }
    lazy val expExpr: PackratParser[Expr] = neg | tightest

    lazy val neg: PackratParser[Expr] = "-" ~ (neg | negExpr) ^^ {
        case _ ~ Num(value) => Num(s"-$value")
        case _ ~ expr => Builtins.Times(-1, expr)
    }
    lazy val negExpr: PackratParser[Expr] = exp | tightest

    lazy val part: PackratParser[Expr] = (part | partExpr) ~ "[[" ~ repsep(expr, ",") ~ "]]" ^^ {
        case expr ~ _ ~ indices ~ _ => Builtins.Part(indices: _*)
    }
    lazy val partExpr: PackratParser[Expr] = tightest

    lazy val span: PackratParser[Expr] =
        expr ~ ";;" ~ expr               ^^ { case i ~ _ ~ j         => Builtins.Span(i, j)                 } |
        expr ~ ";;"                      ^^ { case i ~ _             => Builtins.Span(i, Singletons.All)    } |
               ";;" ~ expr               ^^ { case     _ ~ j         => Builtins.Span(1, j)                 } |
               ";;"                      ^^ { case     _             => Builtins.Span(1, Singletons.All)    } |
        expr ~ ";;" ~ expr ~ ";;" ~ expr ^^ { case i ~ _ ~ j ~ _ ~ k => Builtins.Span(i, j, k)              } |
        expr ~ ";;"        ~ ";;" ~ expr ^^ { case i ~ _     ~ _ ~ k => Builtins.Span(i, Singletons.All, k) } |
               ";;" ~ expr ~ ";;" ~ expr ^^ { case     _ ~ j ~ _ ~ k => Builtins.Span(1, j, k)              } |
               ";;" ~        ";;" ~ expr ^^ { case     _     ~ _ ~ k => Builtins.Span(1, Singletons.All, k) }

    lazy val tightest: PackratParser[Expr] = group | value

    lazy val group: PackratParser[Expr] = "(" ~> expr <~ ")"
    lazy val value: PackratParser[Expr] = apply | list | symbol | number | str

    lazy val expr: PackratParser[Expr] = or | and | not | eq | cmp | add | mul | exp | neg | tightest

    lazy val mathematica: PackratParser[Expr] = expr
}

sealed trait ParseOutput {
    def toPrettyForm: String
}

case class ParseResult(expr: Expr) extends ParseOutput {
    def toPrettyForm = expr.toPrettyForm
}

case class ParseError(msg: String, file: String, pos: Position) extends ParseOutput {
    def message: String = {
        val fileName = (new java.io.File(file)).getName()
        s"$fileName:$pos failure: $msg\n\n${pos.longString}"
    }

    def toPrettyForm = message
}

object MathematicaParser {
    def parse(source: String, name: String = "<string>"): ParseOutput = {
        val parser = new MathematicaParser
        parser.parseAll(parser.mathematica, source) match {
            case parser.Success(expr, _) =>
                ParseResult(expr)
            case parser.NoSuccess(msg, ctx) =>
                ParseError(msg, name, ctx.pos)
        }
    }

    def parse(source: File): ParseOutput =
        parse(FileUtils.readFromFile(source), source.getPath)
}

object FileUtils {
    def readFromFile(file: File): String =
        Source.fromFile(file).mkString("\n")
}

object Main extends App {
    if (args.length > 0) {
        println(MathematicaParser.parse(args.mkString(" ")).toPrettyForm)
    } else {
        println("Nothing to do.")
    }
}
