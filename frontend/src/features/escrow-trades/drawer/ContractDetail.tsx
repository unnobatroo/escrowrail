import { useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";

/** /contracts/:id redirects to the dashboard with the drawer open — keeps old links working; the drawer is the only detail surface. */
export default function ContractDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  useEffect(() => {
    navigate(`/dashboard?trade=${encodeURIComponent(id)}`, { replace: true });
  }, [id, navigate]);
  return null;
}
