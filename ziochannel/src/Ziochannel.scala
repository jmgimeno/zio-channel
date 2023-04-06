package ziochannel

import zio.*

/**
 * An implementation of a thread-safe, blocking channel.
 *
 * @param queue
 *   the underlying queue used to store messages
 * @param done
 *   a promise that is completed when the channel is closed
 * @tparam A
 *   the type of messages in the queue
 */
class Channel[A] private (queue: Channel.ChanQueue[A], done: Promise[Nothing, Boolean], capacity: Int):

  /**
   * Sends a message to the channel and blocks until the message is received by
   * some fiber or the channel closes.
   *
   * @param a
   *   the message to send
   * @return
   *   a `UIO` representing the completion of the send operation
   */
  def send(a: A): UIO[Unit] =
    for
      promise <- Promise.make[Nothing, A]
      _       <- queue.offer((promise, a))
      _       <- queue.size.flatMap(size => if size >= capacity then promise.await else ZIO.unit)
    yield ()

  /**
   * Receives a message from the queue, If no message is available, block until
   * a message is available or the channel closes.
   *
   * @return
   *   a `UIO` containing an `Either` with a `Right[message]` or a
   *   `Left[Closed]`
   */
  def receive: ZIO[Any, Nothing, Either[Closed, A]] =
    for
      tuple       <- queue.take
      (promise, a) = tuple
      isDone      <- done.isDone
      _           <- promise.succeed(a)
    yield if isDone then Left(Closed) else Right(a)

  /**
   * Returns the current status of the channel. If the channel is closed, the
   * returned value will be `Left(Closed)`. Otherwise, the returned value will
   * be `Right(size)`, where `size` is the number of messages in the queue.
   *
   * @return
   *   a `Either[Closed, Int]` representing the current status of the channel
   */
  def status: UIO[Either[Closed, Int]] =
    for
      isDone <- done.isDone
      size   <- queue.size
    yield if isDone then Left(Closed) else Right(size)

  /**
   * Closes the channel, removing any pending messages and unblocking all
   * waiting fibers.
   *
   * @return
   *   a `UIO[Closed]` representing the completion of the close operation
   */
  def close: UIO[Closed] =
    for
      _ <- done.succeed(true)
      _ <- queue.shutdown
    yield Closed

/** A sealed trait representing a closed channel. */
sealed trait Closed

/** Object representing a closed channel. */
object Closed extends Closed

/**
 * An implementation of a thread-safe, blocking channel. This is based on the
 * Golang channels, `Channel` implementation in Stackless Python, and also on
 * Erlang message passing with mailbox.
 *
 * Effects can send messages to the queue using the `send` method, which will
 * block until the message is received. Effects can also receive messages from
 * the queue using the `receive` method, which will block until a message is
 * available.
 *
 * @tparam A
 *   the type of messages in the queue
 */
object Channel:
  private type ChanQueue[A] = Queue[(Promise[Nothing, A], A)]

  /**
   * Creates a new instance of the `Channel` object. The `capacity` parameter
   * determines the maximum number of messages that can be stored in the queue.
   * If `capacity` is 0, the channel will block senders until message is
   * received.
   *
   * @tparam A
   *   the type of messages in the queue
   * @param capacity
   *   the maximum number of messages that can be stored in the queue
   * @return
   *   a new instance of the `Channel` object
   */
  def make[A](capacity: Int): UIO[Channel[A]] =
    for
      queue <- Queue.bounded[(Promise[Nothing, A], A)](capacity)
      done  <- Promise.make[Nothing, Boolean]
    yield new Channel(queue, done, capacity)

  def make[A]: UIO[Channel[A]] = make(1)
