// Serves the static Vite build from Sites' ASSETS binding.
export default {
  async fetch(request, env) {
    return env.ASSETS.fetch(request);
  },
};
