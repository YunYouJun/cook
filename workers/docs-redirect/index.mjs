/** Redirect legacy documentation requests without changing their path or query. */
export default {
  /**
   * @param {Request} request Incoming documentation request.
   * @returns {Response} A permanent redirect to the official product site.
   */
  fetch(request) {
    const target = new URL(request.url)
    target.protocol = 'https:'
    target.hostname = 'cook.yunle.fun'
    target.port = ''
    return Response.redirect(target.toString(), 301)
  },
}
