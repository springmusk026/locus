import unittest

from shopkit.cart import Cart


class CartTest(unittest.TestCase):
    def test_summary(self):
        cart = Cart()
        cart.add("TEA-EG", "Earl Grey", 650, 2)
        cart.add("MUG-10", "Enamel mug", 1200)
        self.assertEqual(cart.summary("SAVE10"), "2 x Earl Grey: €13.00\n1 x Enamel mug: €12.00\nTotal: €22.50")
