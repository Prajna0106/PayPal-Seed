/** @type {import('next').NextConfig} */
const nextConfig = {
  // Request logs would include PayPal's one-time code from the login return URL.
  logging: { incomingRequests: false },
};
export default nextConfig;
