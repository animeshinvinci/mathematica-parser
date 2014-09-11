package org.refptr.parsing.mathematica

sealed trait Expr {
    def toPrettyForm: String
    def toLispForm: String

    def apply(exprs: Expr*): Eval = Eval(this, exprs: _*)
}

case class Sym(name: String) extends Expr {
    def toPrettyForm = name
    def toLispForm = '"' + name + '"'
}

case class Num(value: String) extends Expr {
    def toPrettyForm = value
    def toLispForm = value
}

case class Str(value: String) extends Expr {
    def toPrettyForm = '"' + value.replace("\"", "\\\\\"") + '"'
    def toLispForm = toPrettyForm
}

case class Eval(head: Expr, args: Expr*) extends Expr {
    private def toForm(fn: Expr => String): (String, String) = {
        (fn(head), args.map(fn).mkString(", "))
    }

    def toPrettyForm = {
        val (head, args) = toForm(_.toPrettyForm)
        s"$head[$args]"
    }

    def toLispForm = {
        val (head, args) = toForm(_.toLispForm)
        s"[$head, $args]"
    }

    override def toString = {
        val (head, args) = toForm(_.toString)
        s"Eval($head, $args)"
    }
}

object Implicits {
    implicit def intToExpr(value: Int): Expr = Num(value.toString)
    implicit def doubleToExpr(value: Double): Expr = Num(value.toString)
    implicit def stringToExpr(value: String): Expr = Str(value)
    implicit def symbolToExpr(value: Symbol): Expr = Sym(value.name)
    implicit def booleanToExpr(value: Boolean): Expr = if (value) Sym("True") else Sym("False")
}
