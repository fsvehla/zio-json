package zio.json.ast

import zio.test.Assertion._
import zio.test._

object JsonSpec extends DefaultRunnableSpec {
  val spec: ZSpec[Environment, Failure] =
    suite("Json")(
      suite("equals")(
        test("mismatched Json subtypes") {
          val nul: Json  = Json.Null
          val num: Json  = Json.Num(1)
          val str: Json  = Json.Str("hello")
          val bool: Json = Json.Bool(true)
          val arr: Json  = Json.Arr(nul, num, str)
          val obj: Json = Json.Obj(
            "nul"  -> nul,
            "num"  -> num,
            "str"  -> str,
            "bool" -> bool,
            "arr"  -> arr,
            "obj"  -> Json.Obj("more" -> str, "andMore" -> bool)
          )

          assert(
            List(nul, num, str, bool, arr, obj).combinations(2).forall {
              case fst :: snd :: Nil => fst != snd
              case _                 => false
            }
          )(equalTo(true))
        },
        test("object order does not matter for equality") {
          val obj1 = Json.Obj(
            "foo" -> Json.Str("1"),
            "bar" -> Json.Str("2"),
            "baz" -> Json.Arr(Json.Bool(true), Json.Num(2))
          )

          val obj2 = Json.Obj(
            "baz" -> Json.Arr(Json.Bool(true), Json.Num(2)),
            "bar" -> Json.Str("2"),
            "foo" -> Json.Str("1")
          )

          assert(obj1)(equalTo(obj2))
        }
      ),
      suite("hashCode")(
        test("objects with the same elements regardless of order have the same hashCode") {
          val obj1 = Json.Obj(
            "foo" -> Json.Str("1"),
            "bar" -> Json.Str("2"),
            "baz" -> Json.Arr(Json.Bool(true), Json.Num(2))
          )

          val obj2 = Json.Obj(
            "baz" -> Json.Arr(Json.Bool(true), Json.Num(2)),
            "bar" -> Json.Str("2"),
            "foo" -> Json.Str("1")
          )

          assert(obj1.hashCode)(equalTo(obj2.hashCode))
        }
      ),
      suite("delete")(
        test("removes object entries") {
          val transformed = tweet.delete(JsonCursor.field("user").isObject.field("name"))

          assert(transformed)(
            isRight(
              equalTo(
                Json.Obj(
                  "id" -> Json.Num(8500),
                  "user" -> Json.Obj(
                    "id" -> Json.Num(6200)
                  ),
                  "entities" -> Json.Obj(
                    "hashtags" -> Json.Arr(
                      Json.Str("twitter"),
                      Json.Str("developer")
                    )
                  )
                )
              )
            )
          )
        },
        test("removes array entries") {
          val transformed = tweet.delete(JsonCursor.field("entities").isObject.field("hashtags").isArray.element(1))

          assert(transformed)(
            isRight(
              equalTo(
                Json.Obj(
                  "id" -> Json.Num(8500),
                  "user" -> Json.Obj(
                    "id"   -> Json.Num(6200),
                    "name" -> Json.Str("Twitter API")
                  ),
                  "entities" -> Json.Obj(
                    "hashtags" -> Json.Arr(
                      Json.Str("twitter")
                    )
                  )
                )
              )
            )
          )
        },
        test("identity") {
          assert(Json.Str("hello").delete(JsonCursor.identity))(
            isRight(
              equalTo(Json.Null)
            )
          )
        },
        test("removes filtered cursors only if the type matches") {
          val transformed = tweet.delete(JsonCursor.field("user").isObject.field("id").isString)

          assert(transformed)(isRight(equalTo(tweet)))
        }
      ),
      suite("foldUp")(
        test("folds the structure bottom-up (starting at the leaves)") {
          val obj =
            Json.Obj(
              "one" -> Json.Obj(
                "three" -> Json.Obj(
                  "five" -> Json.Obj(
                    "seven" -> Json.Arr(Json.Str("eight")),
                    "six"   -> Json.Num(6)
                  ),
                  "four" -> Json.Null
                ),
                "two" -> Json.Bool(true)
              )
            )
          val result = obj.foldUp(Vector.empty[String])(collectObjKeysAndArrElements)
          assert(result)(equalTo(Vector("eight", "seven", "six", "five", "four", "three", "two", "one")))
        }
      ),
      suite("foldDown")(
        test("folds the structure top-down (starting at the root)") {
          val obj =
            Json.Obj(
              "one" -> Json.Obj(
                "two" -> Json.Bool(true),
                "three" -> Json.Obj(
                  "four" -> Json.Null,
                  "five" -> Json.Obj(
                    "six"   -> Json.Num(6),
                    "seven" -> Json.Arr(Json.Str("eight"))
                  )
                )
              )
            )
          val result = obj.foldDown(Vector.empty[String])(collectObjKeysAndArrElements)
          assert(result)(equalTo(Vector("one", "two", "three", "four", "five", "six", "seven", "eight")))
        }
      ),
      test("arrays with the same elements in a different order will not have the same hashCode") {
        val arr1 = Json.Arr(Json.Str("one"), Json.Obj("two" -> Json.Num(2)), Json.Num(3))
        val arr2 = Json.Arr(Json.Num(3), Json.Str("one"), Json.Obj("two" -> Json.Num(2)))
        assert(arr1.hashCode)(not(equalTo(arr2.hashCode)))
      },
      test("arrays with the same elements in the same order will have the same hashCode") {
        val arr1 = Json.Arr(Json.Str("one"), Json.Obj("two" -> Json.Num(2)), Json.Num(3))
        val arr2 = Json.Arr(Json.Str("one"), Json.Obj("two" -> Json.Num(2)), Json.Num(3))
        assert(arr1.hashCode)(equalTo(arr2.hashCode))
      },
      suite("transformDownWithCursor")(
        test("scalar") {
          val transformed = Json.Str("hello").transformDownSomeWithCursor { case (Json.Str(s), JsonCursor.identity) =>
            Json.Str("world")
          }

          assert(transformed)(equalTo(Json.Str("world")))
        },
        test("arrays") {
          val cursor = JsonCursor.field("entities").isObject.field("hashtags").isArray.element(1)

          val transformed = tweet.transformDownSomeWithCursor { case (Json.Str(x), `cursor`) =>
            Json.Str(List(x, x, x).mkString(", "))
          }

          assert(transformed)(
            equalTo(
              Json.Obj(
                "id" -> Json.Num(8500),
                "user" -> Json.Obj(
                  "id"   -> Json.Num(6200),
                  "name" -> Json.Str("Twitter API")
                ),
                "entities" -> Json.Obj(
                  "hashtags" -> Json.Arr(
                    Json.Str("twitter"),
                    Json.Str("developer, developer, developer")
                  )
                )
              )
            )
          )
        }
      ),
      suite("get")(
        test("downField") {
          val obj = Json.Obj("a" -> Json.Num(1), "b" -> Json.Num(2), "c" -> Json.Null)

          assert(obj.get(JsonCursor.field("a")))(isRight(equalTo(Json.Num(1)))) &&
          assert(obj.get(JsonCursor.field("b")))(isRight(equalTo(Json.Num(2)))) &&
          assert(obj.get(JsonCursor.field("c")))(isRight(equalTo(Json.Null))) &&
          assert(obj.get(JsonCursor.field("d")))(isLeft(equalTo("No such field: 'd'")))
        },
        test("downElement") {
          val downHashTags = JsonCursor.field("entities").isObject.field("hashtags").isArray

          val fstHashTag = downHashTags.element(0)
          val sndHashTag = downHashTags.element(1)
          val trdHashTag = downHashTags.element(2)

          assert(tweet.get(fstHashTag))(isRight(equalTo(Json.Str("twitter")))) &&
          assert(tweet.get(sndHashTag))(isRight(equalTo(Json.Str("developer")))) &&
          assert(tweet.get(trdHashTag))(isLeft)
        },
        test("filterType") {
          val filterBool = JsonCursor.filter(JsonType.Bool)
          val filterNum  = JsonCursor.filter(JsonType.Bool)
          val filterStr  = JsonCursor.filter(JsonType.Str)

          assert(Json.Str("test").get(filterStr))(isRight) &&
          assert(Json.Str("test").get(filterBool))(isLeft) &&
          assert(Json.Str("test").get(filterNum))(isLeft)
        },
        test(">>>") {
          val downUser       = JsonCursor.field("user")
          val downId         = JsonCursor.field("id")
          val downUserDownId = downUser.isObject >>> downId

          assert(tweet.get(downUserDownId))(
            isRight(equalTo(Json.Num(6200)))
          )
        }
      )
    )

  val tweet: Json.Obj =
    Json.Obj(
      "id" -> Json.Num(8500),
      "user" -> Json.Obj(
        "id"   -> Json.Num(6200),
        "name" -> Json.Str("Twitter API")
      ),
      "entities" -> Json.Obj(
        "hashtags" -> Json.Arr(
          Json.Str("twitter"),
          Json.Str("developer")
        )
      )
    )

  def collectObjKeysAndArrElements(acc: Vector[String], next: Json): Vector[String] =
    next match {
      case Json.Obj(fields)   => acc ++ fields.map(_._1)
      case Json.Arr(elements) => acc ++ elements.collect { case Json.Str(s) => s }.toVector
      case Json.Bool(_)       => acc
      case Json.Str(_)        => acc
      case Json.Num(_)        => acc
      case Json.Null          => acc
    }
}
